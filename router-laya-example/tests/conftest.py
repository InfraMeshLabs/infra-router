import os

# Importing app.main builds the application from application.yml + the environment, and
# that refuses to start with security enabled and no API key. Tests that exercise
# authentication build their own app with explicit Settings.
os.environ["SECURITY_ENABLED"] = "false"
os.environ.pop("INFRA_NODE_API_KEY", None)
