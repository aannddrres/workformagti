FROM python:3.11.9-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN groupadd --gid 10001 presentation \
    && useradd --uid 10001 --gid presentation --create-home presentation

WORKDIR /app
COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --requirement /app/requirements.txt

COPY __init__.py common.py seed_oracle_demo.py activity_pulse.py qa_regression.py /app/

USER presentation
ENTRYPOINT ["python", "/app/seed_oracle_demo.py"]
CMD ["seed"]
