job_class = Class.new(Object) do
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_MT_MESSAGE_UPDATE_STATUS_QUEUE"]

  def perform(mt_message_id, smsc_name, smsc_message_id, status)
    puts("MT MESSAGE ID: #{mt_message_id}, SMSC NAME: #{smsc_name}, SMSC MESSAGE ID: #{smsc_message_id}, STATUS: #{status}")
  end
end

Object.const_set(ENV["SMPP_MT_MESSAGE_UPDATE_STATUS_WORKER"], job_class)
