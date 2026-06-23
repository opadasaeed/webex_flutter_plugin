#
# Run `pod lib lint webex_calling.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'webex_calling'
  s.version          = '0.1.0'
  s.summary          = 'Flutter plugin for Webex (Full SDK) — calling, meetings, messaging.'
  s.description      = <<-DESC
Flutter bridge for Cisco Webex using the Full WebexSDK pod (calling + meetings + messaging).
Includes a post-install optimization to strip debug symbols and remove Virtual Background assets.
                       DESC
  s.homepage         = 'https://developer.webex.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Webex Flutter Plugin' => 'devsupport@webex.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'WebexSDK', '~> 3.16.0'
  s.platform         = :ios, '15.0'
  s.swift_version    = '5.0'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386'
  }

  s.script_phase = {
    :name => 'Optimize Webex SDK',
    :script => '"${PODS_TARGET_SRCROOT}/scripts/optimize_webex_sdk.sh"',
    :execution_position => :after_compile
  }
end
