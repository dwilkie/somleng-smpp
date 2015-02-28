require_relative "config/sidekiq"
Dir["./jobs/**/*.rb"].each { |f| require f }
