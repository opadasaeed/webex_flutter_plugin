import 'package:flutter_test/flutter_test.dart';
import 'package:webex_calling/webex_calling.dart';
import 'package:webex_calling/webex_calling_platform_interface.dart';
import 'package:webex_calling/webex_calling_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockWebexCallingPlatform
    with MockPlatformInterfaceMixin
    implements WebexCallingPlatform {
  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final WebexCallingPlatform initialPlatform = WebexCallingPlatform.instance;

  test('$MethodChannelWebexCalling is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelWebexCalling>());
  });

  test('getPlatformVersion', () async {
    WebexCalling webexCallingPlugin = WebexCalling();
    MockWebexCallingPlatform fakePlatform = MockWebexCallingPlatform();
    WebexCallingPlatform.instance = fakePlatform;

    expect(await webexCallingPlugin.getPlatformVersion(), '42');
  });
}
