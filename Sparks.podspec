require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'Sparks'
  s.version        = package['version'].gsub(/v|-beta/, '')
  s.summary        = package['description']
  s.author         = package['author']
  s.license        = package['license']
  s.homepage       = package['homepage']
  s.source         = { :git => 'https://github.com/marf/react-native-sparks.git', :tag => "v#{s.version}"}
  s.ios.deployment_target = '7.0'
  s.tvos.deployment_target = '9.0'
  s.preserve_paths = '*.js'
  s.library        = 'z'
  s.source_files = 'ios/Sparks/*.{h,m}'
  s.public_header_files = ['ios/Sparks/Sparks.h']

  s.dependency 'React-Core'
  s.dependency 'SSZipArchive', '~> 2.2.2'
  s.dependency 'JWT', '~> 3.0.0-beta.12'
  s.dependency 'Base64', '~> 1.1'
end
