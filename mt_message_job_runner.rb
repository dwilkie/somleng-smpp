class MtMessageJobRunner
  include Sidekiq::Worker
  sidekiq_options :queue => ENV["SMPP_MT_MESSAGE_QUEUE"]
end
