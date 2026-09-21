# Tool image for the UAT account seeder only.
#
# Deliberately not the presentation tools image: that one carries the whole
# demo pipeline (BeautifulSoup, httpx, pytest, the SQLite baseline reader)
# because it rebuilds a ~600-person organisation from source. This script
# writes eleven rows and needs a driver and a password hasher, nothing else.
FROM python:3.11.9-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --gid 10002 uat \
    && useradd --uid 10002 --gid uat --create-home uat

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --requirement /app/requirements.txt

COPY seed_uat_accounts.py /app/

USER uat
ENTRYPOINT ["python", "/app/seed_uat_accounts.py"]
