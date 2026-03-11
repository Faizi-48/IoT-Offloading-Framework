# Lightweight IoT Node Offloading Framework for Real-Time Edge Analytics

> A custom-developed discrete-event simulator for neighbor-aware one-hop task offloading in resource-constrained IoT networks.

**Authors:** Faizan Haider, Alexandre dos Santos Roque, Edison Pignaton de Freitas  
**Affiliation:** School of Information Technology, Halmstad University, Halmstad, Sweden  
**Paper:** *Lightweight IoT Node Offloading Framework for Real-Time Edge Analytics* (IoT 2026)

---

## Overview

This repository contains the Java-based discrete-event simulator used to evaluate the adaptive task offloading framework presented in the paper. The simulator models a heterogeneous IoT network where resource-constrained nodes can offload computational tasks to their one-hop neighbors using a dual-condition, pressure-aware decision algorithm.

The core idea is simple: instead of offloading randomly or always executing locally, each node tracks its neighbors' recent load **and** how many tasks it has recently sent to each of them. This prevents load funneling — the tendency to overwhelm a small set of fast neighbors — while keeping latency low and dropping zero tasks. Critically, this **neighbor selection** is what separates a smart offloading strategy from a naive one: without it, offloading merely relocates congestion rather than resolving it.

---

## Key Features

- **EWMA-based load monitoring** — each node maintains a smoothed load estimate using Exponential Weighted Moving Average (α = 0.40)
- **Dual-condition offloading gate** — offloading is only triggered when *both* EWMA load exceeds a threshold (0.80) *and* the local queue depth is at or above a minimum gate value, preventing false-alarm offloads under transient spikes
- **Assignment-pressure scoring** — a sliding-window counter tracks how many tasks have recently been forwarded to each neighbor, penalizing overused nodes:

```
score = 0.6 × ewmaLoad + 0.4 × assignmentPressure
```

- **Ring-plus-chord topology** — 90-node network where each node connects to neighbors ±1, ±2, ±3 (mod N), giving exactly 6 neighbors per node
- **Heterogeneous nodes** — 20% hotspot nodes (low MIPS, high arrival rate) and 80% light nodes (high MIPS, low arrival rate)
- **Three strategies compared** — Local Only, Proposed Solution without Neighbor Selection, and Proposed Solution with Neighbor Selection

---

## Simulation Results

| Strategy | Avg Latency | Drop Rate | Task Variance | Offload % |
|----------|-------------|-----------|---------------|-----------|
| Local Only | 824.6 ms | 14.00% | 205.97 | 0.00% |
| Proposed w/o Neighbor Selection | 120.1 ms | 0.00% | 114.84 | 54.10% |
| **Proposed w/ Neighbor Selection** | **148.7 ms** | **0.00%** | **44.33** | **28.21%** |

The proposed solution with neighbor selection achieves:
- ✅ **Zero task drop rate** (vs. 14% for local-only)
- ✅ **4.6× lower load variance** vs. local-only execution
- ✅ **2.6× lower load variance** vs. proposed solution without neighbor selection
- ✅ **28.21% offload rate** (vs. 54.10% without neighbor selection — nearly half the communication overhead)

---

## Simulation Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| N | 90 | Total number of nodes |
| Hotspot ratio | 20% | Nodes with heavy load |
| Hotspot MIPS | 4500–5500 | Low-capacity processing |
| Light node MIPS | 11000–14000 | High-capacity processing |
| Task size | 500–2000 MI | Uniform distribution |
| Hotspot arrival interval | 160 ms | Mean inter-arrival time |
| EWMA α | 0.40 | Smoothing factor |
| Offload threshold | 0.80 | EWMA load gate |
| Queue gate | 1 | Min queue depth to offload |
| Pressure window | 400 ms | Assignment pressure sliding window |
| Max queue depth | 8 | Drop threshold per node |
| TX delay | 5 ms | One-hop transmission delay |
| Simulation duration | 10 s | Total simulation time |

---

## Project Structure

```
├── IoTNodeOffloadingSimulator.java   # Full simulator: nodes, scheduler, decision engine, metrics
├── results/
│   ├── success_rate_vs_network_load.png
│   └── latency_vs_num_tasks.png
└── README.md
```

---

## Getting Started

### Prerequisites

- Java 11 or higher
- No external dependencies required

### Build & Run

```bash
# Clone the repository
git clone https://github.com/Faizi-48/IoT-Offloading-Framework.git
cd IoT-Offloading-Framework

# Compile
javac IoTNodeOffloadingSimulator.java

# Run the simulation
java IoTNodeOffloadingSimulator
```

### Output

The simulator prints per-strategy metrics to stdout:

```
Strategy: LOCAL_ONLY
  Avg Latency:    824.6 ms
  Drop Rate:      14.00%
  Task Variance:  205.97
  Offload Rate:   0.00%

Strategy: WITHOUT_NEIGHBOR_SELECTION
  Avg Latency:    120.1 ms
  Drop Rate:      0.00%
  Task Variance:  114.84
  Offload Rate:   54.10%

Strategy: WITH_NEIGHBOR_SELECTION
  Avg Latency:    148.7 ms
  Drop Rate:      0.00%
  Task Variance:  44.33
  Offload Rate:   28.21%
```

---

## How the Algorithm Works

1. A task arrives at a node
2. The node's `LoadMonitor` updates its EWMA load estimate
3. `DecisionEngine` checks both gate conditions:
   - Is EWMA load > 0.80?
   - Is local queue depth ≥ 1?
4. If **both** are true → evaluate all neighbors using the composite score
5. The neighbor with the **lowest score** receives the task via one-hop transmission
6. The origin node increments that neighbor's assignment-pressure counter
7. After execution, both nodes refresh their EWMA load

> **Why neighbor selection matters:** without step 4–5, tasks are forwarded randomly, producing a task variance of 114.84. With neighbor selection, variance drops to 44.33 — a 2.6× improvement — because the assignment-pressure score actively prevents any single neighbor from being overwhelmed.

---

## Citation

If you use this simulator in your work, please cite:

```bibtex
@article{haider2026iot,
  title       = {Lightweight {IoT} Node Offloading Framework for Real-Time Edge Analytics},
  author      = {Haider, Faizan and dos Santos Roque, Alexandre and Pignaton de Freitas, Edison},
  institution = {Halmstad University}
}
```

---

## Contact

- Faizan Haider — faihai25@student.hh.se
- Alexandre dos Santos Roque — alexandre.roque@hh.se
- Edison Pignaton de Freitas — edison.pignaton@hh.se
