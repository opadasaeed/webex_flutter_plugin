class WebexCallingConfig {
  const WebexCallingConfig({
    required this.clientId,
    required this.clientSecret,
    required this.redirectUri,
    this.email,
    this.additionalScopes = const [],
  });

  final String clientId;
  final String clientSecret;
  final String redirectUri;
  final String? email;
  final List<String> additionalScopes;

  Map<String, dynamic> toJson() => {
        'clientId': clientId,
        'clientSecret': clientSecret,
        'redirectUri': redirectUri,
        'email': email,
        'additionalScopes': additionalScopes,
      };
}
