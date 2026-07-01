---
title: Introduction
description: What Altcontainers is and when to use it.
---

# Introduction

Altcontainers provides a programmatic Java API for Docker container lifecycle management. It is designed for integration tests, development workflows, and any scenario where Java code needs to start and stop Docker containers.

## What Altcontainers provides

- Create, start, and destroy Docker containers from Java code
- Wait for containers to be ready (port open, HTTP response, log message)
- Manage Docker bridge networks for container-to-container communication
- Automatic cleanup via JVM shutdown hook (reaper)
- Retry failed startups with configurable backoff
- Shaded dependencies — no classpath conflicts

## How Altcontainers compares to Testcontainers

Altcontainers is a focused container lifecycle library. It does not provide database containers, Kafka containers, Selenium containers, or any other specialized module. It gives you the primitives — `ContainerManager`, `ContainerSpec`, `Container` — and you build on top of them.

Key differences:

- **No module ecosystem.** Altcontainers is a single JAR with no specialized container wrappers.
- **Shaded uber-JAR.** All dependencies are relocated; no version conflicts with your project.
- **Explicit lifecycle.** You control when containers are created and destroyed.
- **Reaper-based cleanup.** Containers and networks are destroyed on JVM exit, even after `System.exit()`.

## When to use Altcontainers

- **Integration tests** that need Docker containers
- **Paramixel tests** (Altcontainers is the container layer for the [Paramixel](https://www.paramixel.org) test orchestration framework)
- **Development scripts** that manage container lifecycles
- **Any Java project** that needs programmatic Docker container control

## Learn next

- [Installation](installation)
- [Your First Container](first-container)
- [Core Concepts: Container Lifecycle](../core-concepts/container-lifecycle)
