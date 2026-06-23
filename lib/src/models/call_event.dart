enum CallEventType {
  connected,
  disconnected,
  ringing,
  holdChanged,
  muteChanged,
  phoneServicesReady,
  phoneServicesFailed,
  unknown,
}

class CallEvent {
  const CallEvent({
    required this.type,
    this.callId,
    this.reason,
    this.isOnHold,
    this.isMuted,
  });

  final CallEventType type;
  final String? callId;
  final String? reason;
  final bool? isOnHold;
  final bool? isMuted;

  factory CallEvent.fromJson(Map<dynamic, dynamic> json) {
    return CallEvent(
      type: CallEventType.values.firstWhere(
        (value) => value.name == json['type'],
        orElse: () => CallEventType.unknown,
      ),
      callId: json['callId'] as String?,
      reason: json['reason'] as String?,
      isOnHold: json['isOnHold'] as bool?,
      isMuted: json['isMuted'] as bool?,
    );
  }
}
