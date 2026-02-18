# Human-vs-CPU-Hashiwokakero-Bridges-Puzzle-Game-


🧩 Problem Description

Each island in the puzzle has a number that represents the exact number of bridges that must be connected to it.

The objective of the game is to:

Connect all islands

Ensure exact degree constraints for every island

Avoid any bridge crossings

Form one fully connected graph

This makes the problem constraint-heavy and irreversible, where early incorrect decisions can lead to unsolvable states.

📜 Game Rules

Bridges can only be placed horizontally or vertically

No bridges may cross each other

A maximum of two bridges can connect the same pair of islands

Each island must satisfy its exact required degree

The final configuration must form one connected component

🏗️ System Design

The system follows a layered architecture:

User Interface (Java Swing)
        ↓
Game State Management
        ↓
Graph Algorithms (BFS, Connected Components)
        ↓
Greedy CPU Decision Engine

Benefits of this Design

Clean and modular code structure

Easy maintainability and extensibility

Strong correctness guarantees

🧮 Graph Representation

The puzzle is internally modeled as a graph, enabling the use of classical graph algorithms.

Game Element	Graph Concept
Island	Vertex (Node)
Bridge	Undirected Edge
Required Number	Degree Constraint

This representation enables efficient graph traversal and connectivity checking.

⚙️ Algorithms Used
1️⃣ Breadth-First Search (BFS)

Used to check graph connectivity

Ensures the puzzle solution forms one connected component

2️⃣ Connected Component Detection

Identifies isolated subgraphs

Guides CPU decisions to preserve global connectivity

3️⃣ Greedy Algorithm

Resolves the most constrained islands first

Avoids dead-end states early

4️⃣ Borůvka-Inspired Strategy

Prefers connections between different components

Prevents the formation of isolated islands

🤖 Greedy CPU Strategy

The CPU follows a 7-step deterministic strategy:

Generate all legal bridge candidates

Apply degree constraints

Compute connected components using BFS

Prefer moves that connect different components

Assign a greedy score to remaining moves

Select the best move using a priority queue

Apply the selected move safely

Greedy Heuristic Used
score = remaining_degree(island A) + remaining_degree(island B)


Lower score → more constrained → higher priority

Ensures urgent constraints are resolved early

✅ Proof of Correctness (Summary)

The game maintains the following invariants at all times:

All moves are validated before execution

Degree constraints are never violated

Bridges never cross

BFS guarantees correct connectivity checking

The puzzle is declared solved if and only if all rules are satisfied

Thus, the system is correct by invariant preservation
