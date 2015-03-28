require_relative 'config/sidekiq'
require_relative 'jobs/mt_message_job_runner'

MtMessageJobRunner.perform_async(
  '0',
  'smart',
  ENV['SMART_SMPP_DEFAULT_SOURCE_ADDRESS'],
  ENV['SMART_SMPP_TEST_MT_NUMBER'],
  ENV['SMART_SMPP_TEST_MT_MESSAGE_TEXT'],
)
