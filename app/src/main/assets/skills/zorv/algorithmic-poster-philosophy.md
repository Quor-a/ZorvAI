---
name: algorithmic-poster-philosophy
description: 当用户提到海报、视觉设计、品牌视觉、排版系统、极简风格、设计哲学、美学方案，或说"帮我做张海报"时，必须触发此技能。该技能先构建一套设计哲学（Algorithmic Philosophy），再基于该哲学用 show_widget 生成可渲染的 SVG 海报 artifact。即使用户没有明确提到"哲学"或"系统"，只要涉及视觉设计或海报生成，都应触发。适用于品牌视觉、作品集封面、极简主义海报、AI 生成艺术、任何需要系统性美学表达的场景。
---

# Algorithmic Poster Philosophy Generator

## Overview

This skill transforms the task from:
- "generate a design"

into:
- "create a design philosophy and express it visually"

Philosophy always comes first.  
The visual output is only an expression of the system.

**Output tools**:
- Philosophy → written inline as structured markdown
- Poster → rendered as SVG via `show_widget` (never described in text, always rendered)

---

# Workflow

## STEP 1 — Create Algorithmic Philosophy

Before generating any visual output, construct a design philosophy inline as structured markdown.

Write 4–6 paragraphs covering the following:

### 1. Concept (Core Idea)
Define the central aesthetic idea.

- Avoid vague artistic language
- Keep it abstract but actionable
- Must be translatable into design behavior

### 2. Visual Logic
Translate the concept into layout logic:

- Grid vs non-grid systems
- Information density (minimal / dense)
- Whitespace strategy

### 3. System Behavior
Describe how the design behaves:

- Hierarchy (primary / secondary / tertiary)
- Alignment vs deviation
- Rhythm, repetition, offset

### 4. Parametric Thinking
Convert design into variables:

- Font size ratios (e.g. title : subtitle : meta = 10 : 3 : 1)
- Alignment rules (e.g. left-anchored with 1 intentional break)
- Margin ratios (e.g. top margin = 15% of height)
- Color count (≤ 2 recommended)
- Number of active elements (≤ 5 recommended)

Avoid result-based descriptions (e.g. "cool style").  
Focus on controllable parameters.

### 5. Emergence
Explain what the system produces when executed:

- Visual feeling
- Perceived structure
- Emotional tone

---

## CRITICAL GUIDELINES (Philosophy Stage)

- Avoid redundancy
- Every sentence must be convertible into design rules
- No purely decorative or empty language
- Think like both a designer and a system builder

---

# STEP 2 — Visual Expression (SVG Poster via show_widget)

Based on the philosophy, call `show_widget` to render an inline SVG poster.

**Never describe the poster in text. Always render it directly.**

---

## SVG Poster Specifications

- Format: SVG
- Aspect ratio: 3:4 (recommended: `viewBox="0 0 600 800"`)
- Background: white or near-black only
- Font: load via `<style>@import url('https://fonts.googleapis.com/css2?family=...')</style>` inside SVG
- Rendering: inline via `show_widget`

---

## Design Rules (Derived from Philosophy)

### Information Strategy
- Reduce content automatically — keep only essential text
- Max 3 information blocks (title / secondary / meta)
- Remove anything that doesn't serve the hierarchy

### Layout System
- Use strict alignment OR intentional single deviation (based on philosophy)
- Maintain strong vertical reading flow
- Generous whitespace — let negative space carry weight

### Typography System
- Clear hierarchy: title (large) / secondary (medium) / meta (small)
- Limit to 1–2 typefaces
- Refined, intentional spacing — use `letter-spacing` and `line-height` deliberately

### Color System
- Black / white / grayscale OR low-saturation palette
- Maximum 2 colors (excluding white/black)
- Color must serve hierarchy, not decoration

### Element Control
- Minimal elements only (≤ 5 active elements on canvas)
- No decorative icons, illustrations, or ornaments
- Design relies on layout, typography, and space — not graphics
- Thin rules or geometric lines allowed only if they reinforce structure

---

# ADVANCED PRINCIPLES

## Concept Embedding

The user's concept must NOT be written explicitly on the poster.

Instead, embed it into:
- Structure and grid logic
- Spacing rhythm
- Typographic alignment or deviation

Think: "A hidden reference inside the system — felt, not read."

---

## Controlled Chaos

Variation is allowed, but always within constraints.

This is not randomness.  
This is designed, intentional variation operating within a defined system.

---

## Craftsmanship Standard

The output must feel:

- Balanced and intentional
- Refined through many iterations
- Minimal — nothing added that could be removed

Like a poster that a senior designer spent a week on.

---

# Output Format

## 1 — Philosophy
Written inline as structured markdown (4–6 paragraphs, following the 5-section structure above).

## 2 — Poster
Rendered directly as SVG via `show_widget`.  
Aspect ratio 3:4. Typography-driven. No decorative elements.  
The poster is the system made visible — not a summary of it.

---

# Full Example

## Input
> "帮我做一张关于'沉默'主题的极简海报"

## Philosophy Output (inline markdown)

**Concept**: Silence is not the absence of sound — it is the space between signals. The design treats whitespace as the primary element, with text as intrusion.

**Visual Logic**: Near-empty grid. One dominant typographic anchor. All secondary information pushed to the margins. Density approaches zero.

**System Behavior**: Single primary element centered or offset by exactly one grid unit. Secondary text at 8% opacity or reduced weight — present but receding. No tertiary elements.

**Parametric Thinking**: Title font-size = 11% of canvas height. Margin = 18% on all sides. Secondary text = title size × 0.18. Color count = 1 (black on white). Active elements = 2.

**Emergence**: The viewer experiences stillness. The eye finds one point, then rests. Meaning accumulates in what is not shown.

## Poster Output
→ `show_widget` renders a 600×800 SVG:  
Single large word near vertical center, slight left offset. One line of small meta text at bottom-right. No rules, no borders. Pure typographic silence.

---

# Key Principle

Do NOT generate just a poster.

ALWAYS generate:
→ a philosophy system  
→ then express it as a rendered SVG artifact via `show_widget`
