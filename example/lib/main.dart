import 'package:flutter/material.dart';
import 'package:webex_calling/webex_calling.dart';

void main() {
  runApp(const WebexCallingExampleApp());
}

class WebexCallingExampleApp extends StatelessWidget {
  const WebexCallingExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Webex Calling',
      theme: ThemeData(colorSchemeSeed: Colors.blue, useMaterial3: true),
      home: const CallingHomePage(),
    );
  }
}

class CallingHomePage extends StatefulWidget {
  const CallingHomePage({super.key});

  @override
  State<CallingHomePage> createState() => _CallingHomePageState();
}

class _CallingHomePageState extends State<CallingHomePage> {
  final _phoneController = TextEditingController();
  String _status = 'Idle';
  double _downloadProgress = 0;

  @override
  void dispose() {
    _phoneController.dispose();
    super.dispose();
  }

  Future<void> _prepareCallingModule() async {
    setState(() => _status = 'Checking module...');
    try {
      await WebexCalling.instance.ensureModuleInstalled(
        onProgress: (state) {
          setState(() {
            _downloadProgress = state.progress;
            _status = 'Module: ${state.status.name}';
          });
        },
      );
      setState(() => _status = 'Module ready');
    } catch (error) {
      setState(() => _status = 'Module error: $error');
    }
  }

  Future<void> _initializeWebex() async {
    setState(() => _status = 'Initializing Webex...');
    await WebexCalling.instance.initialize(
      const WebexCallingConfig(
        clientId: 'YOUR_CLIENT_ID',
        clientSecret: 'YOUR_CLIENT_SECRET',
        redirectUri: 'YOUR_REDIRECT_URI',
      ),
    );
    final phoneStatus = await WebexCalling.instance.getPhoneServicesStatus();
    setState(() => _status = 'Phone services: $phoneStatus');
  }

  Future<void> _dial() async {
    final phoneNumber = _phoneController.text.trim();
    if (phoneNumber.isEmpty) {
      setState(() => _status = 'Enter a phone number');
      return;
    }

    setState(() => _status = 'Dialing...');
    final callId = await WebexCalling.instance.dial(phoneNumber);
    setState(() => _status = 'Call started: $callId');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Webex Calling Plugin')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(_status),
            if (_downloadProgress > 0 && _downloadProgress < 1)
              LinearProgressIndicator(value: _downloadProgress),
            const SizedBox(height: 16),
            TextField(
              controller: _phoneController,
              keyboardType: TextInputType.phone,
              decoration: const InputDecoration(
                labelText: 'Phone number',
                hintText: '+18001234567',
              ),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _prepareCallingModule,
              child: const Text('Download Calling Module (Android)'),
            ),
            FilledButton(
              onPressed: _initializeWebex,
              child: const Text('Initialize Webex'),
            ),
            FilledButton(
              onPressed: _dial,
              child: const Text('Dial'),
            ),
          ],
        ),
      ),
    );
  }
}
