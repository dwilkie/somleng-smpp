source 'https://rubygems.org'
ruby File.read('.ruby-version').strip

group :development do
  gem 'sidekiq'
  gem 'capistrano'
  gem 'capistrano-rbenv'
  gem 'capistrano-bundler'
  #gem 'capistrano-foreman', :github => "hyperoslo/capistrano-foreman"
  gem 'capistrano-foreman', :github => "dwilkie/capistrano-foreman"
end

group :deployment, :development do
  gem 'foreman', :github => "ddollar/foreman" # needed for deployment
end
