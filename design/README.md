# design/

The project layer of the `design-run` process (`~/.claude/skills/design-run/SKILL.md`): every
design decision for J'Toye OaaS, kept as plain text so the next run starts from it and a reviewer
can see why a surface looks the way it does. Sanmi decides; Claude proposes.

| File | Holds |
|---|---|
| `brief.md` | per surface: subject, audience, the one job, real constraints, what imagery exists |
| `references/README.md` | the 3–5 references the owner chose, each with what to take and what not |
| `decisions.md` | brand and identity rulings, each with its source and a **Review when:** trigger |
| `identity.md` | palette, type, voice, the one memorable device (written at step 4 of a run) |
| `rulings.tsv` | skill-conflict rulings, TAB-separated: `rule_id scope decision date who why` |
| `rubric.md` | what "good" means here: the owner's past verdicts as checkable lines |
| `runs/<date>-<surface>/` | one run: direction, gate output, screenshots, scores, verdict |

Older records stay where they are and are cited, not copied: `.planning/sketches/MANIFEST.md`
(sketch rounds 001–007) and the sketch folders beside it.

Check the rulings against the installed design skills:

```bash
~/.claude/skills/design-run/scripts/rule-check.sh conflicts --scope page --rulings design/rulings.tsv
~/.claude/skills/design-run/scripts/rule-check.sh conflicts --scope app  --rulings design/rulings.tsv
```
