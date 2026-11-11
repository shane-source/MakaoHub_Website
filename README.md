# Makao Hub website

For local development, copy `conf/application.conf.example` to `conf/application.conf` and configure the backend URL and support number as needed. The local configuration is ignored by Git. The same settings can be supplied using `MAKAO_API_BASE_URL` and `MAKAO_SUPPORT_WHATSAPP` environment variables.

Run the application with `sbt run`. Never commit credentials, access tokens or private account information.
