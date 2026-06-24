import Flutter
import UIKit
import WebexSDK

public class WebexCallingPlugin: NSObject, FlutterPlugin {
  private var webex: Webex?
  private var activeCall: Call?
  private var eventSink: FlutterEventSink?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(
      name: "webex_calling",
      binaryMessenger: registrar.messenger()
    )
    let events = FlutterEventChannel(
      name: "webex_calling/events",
      binaryMessenger: registrar.messenger()
    )
    let moduleEvents = FlutterEventChannel(
      name: "webex_calling/module_install",
      binaryMessenger: registrar.messenger()
    )

    let instance = WebexCallingPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    events.setStreamHandler(instance)
    moduleEvents.setStreamHandler(ModuleInstallStreamHandler())
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isModuleInstalled":
      result(true)
    case "installModule":
      result(nil)
    case "cancelModuleInstall":
      result(nil)
    case "initialize":
      if Self.isRunningOnSimulator {
        result(Self.simulatorUnsupportedError())
        return
      }

      guard
        let args = call.arguments as? [String: Any],
        let clientId = args["clientId"] as? String,
        let clientSecret = args["clientSecret"] as? String,
        let redirectUri = args["redirectUri"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENT",
            message: "Missing OAuth configuration.",
            details: nil
          )
        )
        return
      }

      let email = args["email"] as? String ?? ""
      let scopes = args["additionalScopes"] as? [String] ?? []
      let scope = scopes.isEmpty ? "spark:all" : scopes.joined(separator: " ")
      let authenticator = OAuthAuthenticator(
        clientId: clientId,
        clientSecret: clientSecret,
        scope: scope,
        redirectUri: redirectUri,
        emailId: email
      )

      webex = Webex(authenticator: authenticator)
      webex?.ucLoginDelegate = self
      webex?.initialize { [weak self] isLoggedIn in
        guard let self else { return }

        // Webex returns whether the user is already authenticated, not init success.
        if isLoggedIn {
          result(true)
          return
        }

        guard let parent = self.topViewController() else {
          result(
            FlutterError(
              code: "NO_VIEW_CONTROLLER",
              message: "Unable to present Webex login screen.",
              details: nil
            )
          )
          return
        }

        authenticator.authorize(parentViewController: parent) { oauthResult in
          if oauthResult == .success {
            result(true)
          } else {
            result(
              FlutterError(
                code: "OAUTH_FAILED",
                message: "OAuth authorization failed: \(oauthResult.rawValue)",
                details: nil
              )
            )
          }
        }
      }
    case "getPhoneServicesStatus":
      result(String(describing: webex?.getUCServerConnectionStatus() ?? UCLoginServerConnectionStatus.Disconnected))
    case "dial":
      guard
        let args = call.arguments as? [String: Any],
        let phoneNumber = args["phoneNumber"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENT",
            message: "phoneNumber is required.",
            details: nil
          )
        )
        return
      }

      let audioOnly = args["audioOnly"] as? Bool ?? true
      let mediaOption =
        audioOnly
        ? MediaOption.audioOnly()
        : MediaOption.audioVideo(local: nil, remote: nil)

      webex?.phone.dialPhoneNumber(phoneNumber, option: mediaOption) { [weak self] dialResult in
        switch dialResult {
        case .success(let call):
          self?.activeCall = call
          call.onConnected = { [weak self] in
            self?.emit(type: "connected", callId: call.callId)
          }
          call.onDisconnected = { [weak self] reason in
            self?.emit(type: "disconnected", callId: call.callId, reason: String(describing: reason))
            if self?.activeCall?.callId == call.callId {
              self?.activeCall = nil
            }
          }
          result(call.callId)
        case .failure(let error):
          result(
            FlutterError(
              code: "DIAL_FAILED",
              message: error.localizedDescription,
              details: nil
            )
          )
        }
      }
    case "joinMeeting":
      guard
        let args = call.arguments as? [String: Any],
        let address = args["address"] as? String
      else {
        result(
          FlutterError(
            code: "INVALID_ARGUMENT",
            message: "address is required.",
            details: nil
          )
        )
        return
      }

      let audioOnly = args["audioOnly"] as? Bool ?? true
      let mediaOption =
        audioOnly
        ? MediaOption.audioOnly()
        : MediaOption.audioVideo(local: nil, remote: nil)

      webex?.phone.dial(address, option: mediaOption) { [weak self] dialResult in
        switch dialResult {
        case .success(let call):
          self?.activeCall = call
          call.onConnected = { [weak self] in
            self?.emit(type: "connected", callId: call.callId)
          }
          call.onDisconnected = { [weak self] reason in
            self?.emit(type: "disconnected", callId: call.callId, reason: String(describing: reason))
            if self?.activeCall?.callId == call.callId {
              self?.activeCall = nil
            }
          }
          result(call.callId)
        case .failure(let error):
          result(
            FlutterError(
              code: "JOIN_FAILED",
              message: error.localizedDescription,
              details: nil
            )
          )
        }
      }
    case "hangup":
      let callId = (call.arguments as? [String: Any])?["callId"] as? String
      resolveCall(callId: callId)?.hangup { _ in }
      result(nil)
    case "setMuted":
      guard let args = call.arguments as? [String: Any] else {
        result(nil)
        return
      }
      let muted = args["muted"] as? Bool ?? false
      resolveCall(callId: args["callId"] as? String)?.sendingAudio = !muted
      result(nil)
    case "hold":
      guard let args = call.arguments as? [String: Any] else {
        result(nil)
        return
      }
      let onHold = args["onHold"] as? Bool ?? false
      resolveCall(callId: args["callId"] as? String)?.holdCall(putOnHold: onHold) { _ in }
      result(nil)
    case "answer":
      let callId = (call.arguments as? [String: Any])?["callId"] as? String
      resolveCall(callId: callId)?.answer(option: MediaOption.audioOnly()) { _ in }
      result(nil)
    case "reject":
      let callId = (call.arguments as? [String: Any])?["callId"] as? String
      resolveCall(callId: callId)?.reject { _ in }
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private static var isRunningOnSimulator: Bool {
    #if targetEnvironment(simulator)
    return true
    #else
    return false
    #endif
  }

  private static func simulatorUnsupportedError() -> FlutterError {
    FlutterError(
      code: "SIMULATOR_UNSUPPORTED",
      message:
        "Webex Calling SDK does not run on the iOS Simulator (including Apple Silicon via Rosetta). "
        + "Connect a physical iPhone or iPad to test calling.",
      details: nil
    )
  }

  private func resolveCall(callId: String?) -> Call? {
    if let callId, activeCall?.callId != callId {
      return nil
    }
    return activeCall
  }

  private func topViewController() -> UIViewController? {
    let keyWindow = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap { $0.windows }
      .first { $0.isKeyWindow }

    var controller = keyWindow?.rootViewController
    while let presented = controller?.presentedViewController {
      controller = presented
    }
    return controller
  }

  private func emit(type: String, callId: String?, reason: String? = nil) {
    eventSink?([
      "type": type,
      "callId": callId as Any,
      "reason": reason as Any,
    ])
  }
}

extension WebexCallingPlugin: FlutterStreamHandler {
  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }
}

extension WebexCallingPlugin: WebexUCLoginDelegate {
  public func loadUCSSOView(to url: String) {
    guard let webex, let parent = topViewController() else {
      emit(type: "ucSsoRequired", callId: nil, reason: url)
      return
    }

    webex.getUCSSOLoginView(parentViewController: parent, ssoUrl: url) { [weak self] success in
      if success == true {
        self?.emit(type: "ucLoggedIn", callId: nil)
      } else {
        self?.emit(type: "ucSsoFailed", callId: nil, reason: url)
      }
    }
  }

  public func showUCNonSSOLoginView() {
    emit(type: "ucCredentialsRequired", callId: nil)
  }

  public func onUCSSOLoginFailed(failureReason: UCSSOFailureReason) {
    emit(type: "ucSsoFailed", callId: nil, reason: String(describing: failureReason))
    webex?.retryUCSSOLogin()
  }

  public func onUCLoggedIn() {
    emit(type: "ucLoggedIn", callId: nil)
  }

  public func onUCLoginFailed(failureReason: UCLoginFailureReason) {
    emit(type: "ucLoginFailed", callId: nil, reason: String(describing: failureReason))
  }

  public func onUCServerConnectionStateChanged(
    status: UCLoginServerConnectionStatus,
    failureReason: PhoneServiceRegistrationFailureReason
  ) {
    if status == .Connected {
      emit(type: "phoneServicesReady", callId: nil)
    } else if status == .Failed {
      emit(type: "phoneServicesFailed", callId: nil, reason: String(describing: failureReason))
    }
  }
}

private final class ModuleInstallStreamHandler: NSObject, FlutterStreamHandler {
  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    events([
      "status": "installed",
      "progress": 1.0,
    ])
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    nil
  }
}
