job_class = Class.new(Object) do
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_MO_MESSAGE_RECEIVED_QUEUE"]

  def perform(smsc_name, source_address, dest_address, message_text)
    puts("SMSC NAME: #{smsc_name}, SOURCE ADDRESS: #{source_address}, DEST ADDRESS: #{dest_address}, MESSAGE TEXT: #{message_text}")
  end
end

Object.const_set(ENV["SMPP_MO_MESSAGE_RECEIVED_WORKER"], job_class)
