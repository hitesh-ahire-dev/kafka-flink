# Kafka–Flink Streaming Pipeline

This project demonstrates a **real-time data streaming pipeline** using **Apache Kafka** and **Apache Flink**.  
It is created to understand how streaming data flows from Kafka to Flink and how Flink processes events in real time.

---

## 📌 Project Overview

In this project:
- **Kafka** acts as the event ingestion and messaging system.
- **Flink** consumes events from Kafka and performs real-time stream processing.
- The pipeline processes user login events to understand Kafka–Flink integration and data flow.

This project focuses on **learning and hands-on understanding** of streaming concepts rather than business logic.

---

## 🧠 Purpose of the Project

- Understand **Kafka producer → topic → consumer** workflow
- Learn how **Flink KafkaSource** reads data from Kafka
- Understand **serialization and deserialization** of streaming events
- Observe **real-time data processing** using Flink
- Get familiar with **end-to-end streaming pipelines**

---

## 🛠 Tech Stack

- Java
- Apache Kafka
- Apache Flink

---

## 🔄 Data Flow Architecture

+-----------+ +-------------+ +------------------+ +---------------------+ +-----------+
| Producer | ---> | Kafka Topic | ---> | Flink KafkaSource | ---> | Stream Processing | ---> | Output |
| (Events) | | user-login | | | | (map, transform) | | Console |
+-----------+ +-------------+ +------------------+ +---------------------+ +-----------+

---

## 📥 Event Flow Explanation

1. A producer sends **user login events** to a Kafka topic (`user-login`).
2. Kafka stores the events in the topic as **byte data**.
3. Flink uses `KafkaSource` to consume messages from the topic.
4. A **deserialization schema** converts Kafka bytes into Java objects.
5. Flink processes the stream in real time (e.g., transformations, logging).
6. The processed output is printed or forwarded to downstream systems.

---

## 📄 Sample Event (Kafka Message)

```json
{
  "userId": "user123",
  "timestamp": 1700000000000
}
