# Declare intent

The human says what they want. Your job is to understand it well enough to draft an RFC they approve
with few revisions. That means knowing what they want, what it is for, and how they will judge that it
works. A draft built on a misread intent wastes the whole loop, so this step is where the questions go.

## 1. Read before you ask

Read the relevant `specs/current/`, the code in the area, and any active or archived RFCs that touch it.
Informed questions are shorter, and nothing wastes the human's time like a question the repository
already answers. If an active RFC already covers this intent, refine that one instead of starting another.

## 2. Interrogate

Work through what the draft will need. Skip what is already clear from the request or the repository.

- **Outcome** — what is different when this is done? What will the human see, do, or stop having to do?
- **Motivation** — what problem does this solve, why now, and what happens if it is never done? The
  underlying goal often allows a better solution than the one first asked for. Say so when it does.
- **Scenarios** — walk through concrete uses, step by step, including the unhappy ones. Cover when
  things go wrong, when the human changes their mind, and the first run versus the hundredth.
- **Scope** — the must-haves, the nice-to-haves, and the explicit non-goals.
- **Success** — how will the human judge that it works? Push for observable signals; these become the
  acceptance criteria.
- **Constraints** — platform, compatibility, security, privacy, performance, and anything that must not
  change.
- **Failure and edge cases** — what should happen when the thing this depends on is missing, slow, or
  wrong?
- **Trade-offs** — when two goals conflict, which one gives way?
- **Prior art** — what they have tried, and what they have seen elsewhere that they liked or disliked.

How to ask:

- A few questions per round (at most four), most consequential first. Use the harness's structured
  question tool if it has one.
- Where you can, offer concrete options with a recommendation, rather than an open "what do you want?"
- Follow vague answers down: "fast" becomes "how fast, measured how?"; "secure" becomes "against whom?"
- Name contradictions between answers, or between an answer and the current specs, and ask which wins.
- Do not ask about details that would not change the design. Record a reasonable default as a labelled
  assumption instead.
- Keep going for as many rounds as it takes. Stop when you could write acceptance criteria the human
  would agree to. Every remaining unknown should be either a labelled assumption or a named open
  question.

Scale this to the change. A one-line fix may need a single confirming question, or none. A new
capability usually needs several rounds.

## 3. Play it back

Write an **intent brief** and ask the human to confirm or correct it:

- **Outcome** — one or two sentences.
- **Motivation** — the problem and why it matters.
- **Scope** — in, and explicitly out.
- **Success signals** — the observable results that will become acceptance criteria.
- **Constraints**.
- **Assumptions** — each labelled as yours, not theirs.
- **Open questions** — each with who decides and what it blocks.

Revise until the human confirms it. A confirmed brief is not design approval. It is agreement on what
the design is for.

## 4. Hand off to drafting

Allocate the RFC with
`python3 "$(git rev-parse --show-toplevel)/.claude/skills/spec-driven/scripts/rfc.py" new "<slug>"`.
Copy the confirmed brief into the RFC's Intent section. Record the notable questions and answers under
Decisions in `notes.md`, so a later session knows why the intent is what it is. Continue with
`procedures/create-rfc.md`.
