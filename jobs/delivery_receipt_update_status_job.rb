job_class = Class.new(Object) do
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_DELIVERY_RECEIPT_UPDATE_STATUS_QUEUE"]

  def perform(smsc_name, smsc_message_id, status)
    puts("SMSC NAME: #{smsc_name}, SMSC MESSAGE ID: #{smsc_message_id}, STATUS: #{status}")
  end
end

Object.const_set(ENV["SMPP_DELIVERY_RECEIPT_UPDATE_STATUS_WORKER"], job_class)
