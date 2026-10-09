FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN useradd --create-home app
WORKDIR /home/app/router

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY application.yml .
COPY app ./app

# The Laya checkpoint (~846MB) is NOT baked into the image: it is downloaded on first
# start, into this user's cache directory. Mount a volume there to keep it across runs.
USER app

EXPOSE 8093

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8093"]
