library;

import 'dart:async';

export 'src/models/call_event.dart';
export 'src/models/module_install_state.dart';
export 'src/models/webex_calling_config.dart';
export 'webex_calling_platform_interface.dart';

import 'src/models/call_event.dart';
import 'src/models/module_install_state.dart';
import 'src/models/webex_calling_config.dart';
import 'webex_calling_platform_interface.dart';

class WebexCalling {
  WebexCalling._();

  static final WebexCalling instance = WebexCalling._();

  WebexCallingPlatform get _platform => WebexCallingPlatform.instance;

  Stream<CallEvent> get callEvents => _platform.callEvents;

  Stream<ModuleInstallState> get moduleInstallStates =>
      _platform.moduleInstallStates;

  Future<bool> isModuleInstalled() => _platform.isModuleInstalled();

  Future<void> installModule() => _platform.installModule();

  Future<void> cancelModuleInstall() => _platform.cancelModuleInstall();

  /// Downloads the on-demand Webex module on Android and waits until ready.
  /// On iOS the Full SDK ships in the main bundle.
  Future<void> ensureModuleInstalled({
    void Function(ModuleInstallState state)? onProgress,
  }) async {
    if (await isModuleInstalled()) {
      onProgress?.call(
        const ModuleInstallState(status: ModuleInstallStatus.installed, progress: 1),
      );
      return;
    }

    final completer = Completer<void>();
    late final StreamSubscription<ModuleInstallState> subscription;

    subscription = moduleInstallStates.listen((state) {
      onProgress?.call(state);
      if (state.isInstalled && !completer.isCompleted) {
        completer.complete();
      } else if (state.status == ModuleInstallStatus.failed &&
          !completer.isCompleted) {
        completer.completeError(
          StateError(state.errorMessage ?? 'Webex Calling module install failed.'),
        );
      }
    });

    try {
      await installModule();
      await completer.future;
    } finally {
      await subscription.cancel();
    }
  }

  Future<void> initialize(WebexCallingConfig config) =>
      _platform.initialize(config);

  Future<String> getPhoneServicesStatus() =>
      _platform.getPhoneServicesStatus();

  Future<String?> dial(String phoneNumber, {bool audioOnly = true}) =>
      _platform.dial(phoneNumber, audioOnly: audioOnly);

  /// Join a meeting via URL, SIP URI, or meeting number (Full SDK).
  Future<String?> joinMeeting(String address, {bool audioOnly = true}) =>
      _platform.joinMeeting(address, audioOnly: audioOnly);

  Future<void> hangup({String? callId}) => _platform.hangup(callId: callId);

  Future<void> setMuted(bool muted, {String? callId}) =>
      _platform.setMuted(muted, callId: callId);

  Future<void> hold(bool onHold, {String? callId}) =>
      _platform.hold(onHold, callId: callId);

  Future<void> answer({String? callId}) => _platform.answer(callId: callId);

  Future<void> reject({String? callId}) => _platform.reject(callId: callId);
}
