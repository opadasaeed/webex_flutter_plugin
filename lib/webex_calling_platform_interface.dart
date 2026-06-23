import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'src/models/call_event.dart';
import 'src/models/module_install_state.dart';
import 'src/models/webex_calling_config.dart';
import 'webex_calling_method_channel.dart';

abstract class WebexCallingPlatform extends PlatformInterface {
  WebexCallingPlatform() : super(token: _token);

  static final Object _token = Object();
  static WebexCallingPlatform _instance = MethodChannelWebexCalling();

  static WebexCallingPlatform get instance => _instance;

  static set instance(WebexCallingPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Stream<CallEvent> get callEvents;

  Stream<ModuleInstallState> get moduleInstallStates;

  Future<bool> isModuleInstalled();

  Future<void> installModule();

  Future<void> cancelModuleInstall();

  Future<void> initialize(WebexCallingConfig config);

  Future<String> getPhoneServicesStatus();

  Future<String?> dial(String phoneNumber, {bool audioOnly = true});

  /// Join a Webex meeting using a meeting URL, SIP URI, or meeting number.
  Future<String?> joinMeeting(String address, {bool audioOnly = true});

  Future<void> hangup({String? callId});

  Future<void> setMuted(bool muted, {String? callId});

  Future<void> hold(bool onHold, {String? callId});

  Future<void> answer({String? callId});

  Future<void> reject({String? callId});
}
