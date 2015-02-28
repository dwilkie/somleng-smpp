require 'sidekiq'

# This should be removed when https://github.com/gresrun/jesque/issues/65 is fixed

Sidekiq.configure_client do |config|
  config.redis = { :namespace => 'resque' }
end

Sidekiq.configure_server do |config|
  config.redis = { :namespace => 'resque' }
end
