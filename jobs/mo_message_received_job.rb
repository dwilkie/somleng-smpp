job_class = Class.new(Object) do
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_MO_MESSAGE_RECEIVED_QUEUE"]

  def perform(smsc_name, source_address, dest_address, message_text, csms_reference_num, csms_num_parts, csms_seq_num)
    puts("SMSC NAME: #{smsc_name}, SOURCE ADDRESS: #{source_address}, DEST ADDRESS: #{dest_address}, MESSAGE TEXT: #{message_text}, CSMS REF NUM #{csms_reference_num}, CSMS NUM PARTS #{csms_num_parts}, CSMS SEQ NUM #{csms_seq_num}")
  end
end

Object.const_set(ENV["SMPP_MO_MESSAGE_RECEIVED_WORKER"], job_class)
