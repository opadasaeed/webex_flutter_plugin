import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'src/models/call_event.dart';
import 'src/models/module_install_state.dart';
import 'src/models/webex_calling_config.dart';
import 'webex_calling_platform_interface.dart';

class MethodChannelWebexCalling extends WebexCallingPlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel('webex_calling');

  @visibleForTesting
  final eventChannel = const EventChannel('webex_calling/events');

  @visibleForTesting
  final moduleEventChannel =
      const EventChannel('webex_calling/module_install');

  @override
  Stream<CallEvent> get callEvents =>
      eventChannel.receiveBroadcastStream().map(
            (event) => CallEvent.fromJson(
              Map<dynamic, dynamic>.from(event as Map),
            ),
          );

  @override
  Stream<ModuleInstallState> get moduleInstallStates =>
      moduleEventChannel.receiveBroadcastStream().map((event) {
        final map = Map<dynamic, dynamic>.from(event as Map);
        return ModuleInstallState(
          status: ModuleInstallStatus.values.firstWhere(
            (value) => value.name == map['status'],
            orElse: () => ModuleInstallStatus.failed,
          ),
          progress: (map['progress'] as num?)?.toDouble() ?? 0,
          errorMessage: map['errorMessage'] as String?,
        );
      });

  @override
  Future<bool> isModuleInstalled() async {
    final installed =
        await methodChannel.invokeMethod<bool>('isModuleInstalled');
    return installed ?? false;
  }

  @override
  Future<void> installModule() =>
      methodChannel.invokeMethod<void>('installModule');

  @override
  Future<void> cancelModuleInstall() =>
      methodChannel.invokeMethod<void>('cancelModuleInstall');

  @override
  Future<void> initialize(WebexCallingConfig config) =>
      methodChannel.invokeMethod<void>('initialize', config.toJson());

  @override
  Future<String> getPhoneServicesStatus() async {
    final status =
        await methodChannel.invokeMethod<String>('getPhoneServicesStatus');
    return status ?? 'unknown';
  }

  @override
  Future<String?> dial(String phoneNumber, {bool audioOnly = true}) async {
    final callId = await methodChannel.invokeMethod<String>('dial', {
      'phoneNumber': phoneNumber,
      'audioOnly': audioOnly,
    });
    return callId;
  }

  @override
  Future<String?> joinMeeting(String address, {bool audioOnly = true}) async {
    final callId = await methodChannel.invokeMethod<String>('joinMeeting', {
      'address': address,
      'audioOnly': audioOnly,
    });
    return callId;
  }

  @override
  Future<void> hangup({String? callId}) =>
      methodChannel.invokeMethod<void>('hangup', {'callId': callId});

  @override
  Future<void> setMuted(bool muted, {String? callId}) =>
      methodChannel.invokeMethod<void>('setMuted', {
        'muted': muted,
        'callId': callId,
      });

  @override
  Future<void> hold(bool onHold, {String? callId}) =>
      methodChannel.invokeMethod<void>('hold', {
        'onHold': onHold,
        'callId': callId,
      });

  @override
  Future<void> answer({String? callId}) =>
      methodChannel.invokeMethod<void>('answer', {'callId': callId});

  @override
  Future<void> reject({String? callId}) =>
      methodChannel.invokeMethod<void>('reject', {'callId': callId});
}
