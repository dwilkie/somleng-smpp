job_class = Class.new(Object) do
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_MT_MESSAGE_UPDATE_STATUS_QUEUE"]

  def perform(smsc_name, mt_message_id, smsc_message_id, status)
    puts("SMSC NAME: #{smsc_name}, MT MESSAGE ID: #{mt_message_id}, SMSC MESSAGE ID: #{smsc_message_id}, STATUS: #{status}")
  end
end

Object.const_set(ENV["SMPP_MT_MESSAGE_UPDATE_STATUS_WORKER"], job_class)
