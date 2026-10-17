---
description: Analyze docs/new-requirements.md, ask about what is unclear, offer options to choose
---

Analyze the new requirements in `docs/new-requirements.md`. Change no code, and no document until every point is clear (steps 7
and 8).

1. **Read the notes.** Read `docs/new-requirements.md`, and every picture it names (`docs/img_<n>.png`) with the Read tool.
   If the file is the same as in the last analysis of this conversation, say so and ask only what is still open.
2. **Ground each note in what exists.** For every note, find what the program does today: the rule in `docs/requirements.md`
   (and `docs/api.md` where the page and the host or the API are concerned), and the code that does it (mostly
   `src/main/resources/web/app.js`, `app.css`, `index.html`, and the Java under `src/main/java/org/example`). Read only what is
   needed to understand the note; delegate a broad search to an Explore agent.
3. **Sort the notes.** For each, decide whether it is clear as written. A note is unclear when it can be read more than one way,
   when it conflicts with an existing rule, or when building it forces a choice the note does not make (a colour, a size, a
   name, a migration of saved settings, what happens in the other views or with the title bar on or off, and so on).
4. **Report**, short, per note: what it asks, what the program does today (with `file:line` where it helps), and what is unclear.
   Put the notes that are clear first, with how they would be built.
5. **Ask.** Use the AskUserQuestion tool for every unclear point, up to four questions per call; another round if more remain
   or if an answer raises a new question. Each question has 2 to 4 concrete options with a short description of what each
   means; the one you recommend first, its label ending with `(Recommended)`. Read the answers as written: a free-text answer
   may change the question, and then ask again rather than guess.
6. **Sum up** the decisions as the plan of the next phase (the number after the last `### <n>.` in
   `docs/implementation-plan.md`, and the next version after `AppInfo.VERSION`), each note with what will be built, and name
   any choice you made yourself so that it can be corrected.
7. **Update `docs/requirements.md`** once every point is clear: each decision as a rule in the section it belongs to (or a new
   section where none fits), in the document's own style, marked `since version <next>`, and what held until then kept as
   `until version <last>`, with the pictures named (`docs/img_<n>.png`). Change `docs/api.md` too where the page, the host or
   the API change.
8. **Then update `docs/implementation-plan.md`**: a new `### <n>. Version <next>: <what>` after the last phase, **Status:
   planned, not built**, with the requirements sections it builds, what is decided, what is built where (file, function,
   rule), the tests to write or change, and what is to be looked at by eye; and the line **Where it stands** at the top brought
   up to date.
9. **Ask whether to build it.** Do not build until asked, and do not commit the documents unless asked.
