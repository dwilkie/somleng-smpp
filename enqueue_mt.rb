require 'sidekiq'
require_relative 'mt_message_job_runner'

# This should be removed when https://github.com/gresrun/jesque/issues/65 is fixed

Sidekiq.configure_client do |config|
  config.redis = { :namespace => 'resque' }
end

MtMessageJobRunner.perform_async(
  'smart',
  ENV['SMART_SMPP_DEFAULT_SOURCE_ADDRESS'],
  ENV['SMART_SMPP_TEST_MT_NUMBER'],
  ENV['SMART_SMPP_TEST_MT_MESSAGE_TEXT'],
)
