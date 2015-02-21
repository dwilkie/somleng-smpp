source 'https://rubygems.org'
ruby '2.2.0'

group :development do
  gem 'sidekiq'
  gem 'capistrano'
  gem 'capistrano-rbenv'
  gem 'capistrano-bundler'
#  gem 'capistrano-foreman', :github => "hyperoslo/capistrano-foreman"
  gem 'capistrano-foreman', :github => "dwilkie/capistrano-foreman", :branch => "interpolate_args"
end

group :deployment, :development do
  gem 'foreman', :github => "ddollar/foreman" # needed for deployment
  gem 'foreman-upstart-su', :github => "dwilkie/foreman-upstart-su", :require => false
end
