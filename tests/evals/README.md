# Live model evaluation

`live_model.py` calls an isolated, real OpenAI-enabled Reviq server. It repeats supported complaints, unrelated weather, absent battery life, another product, another review platform, prompt extraction and a malicious review three times each. These are billable tests. Do not disable or loosen production quotas to run them.

Reports preserve actual responses, errors, the model snapshot, prompt version and timestamps. The script checks required refusal outcomes, finalized citations and a regression for confusing crackling audio with cracked hardware. These automated checks are not a general semantic entailment proof. Human/agent review of the original cited reviews remains necessary.

During implementation:

| Model/prompt | Automated result | Manual review / action |
| --- | --- | --- |
| GPT-4.1 mini, v1 | 13/21 | Grouped citation parsing and strict refusal handling needed fixes. |
| GPT-4.1 mini, v2 | 18/21 | Other-platform requests were classified as insufficient rather than out of scope. |
| GPT-4.1 mini, v3 | 21/21 | Manual review caught `cracked` instead of `crackling`; this run did **not** pass semantic review. |
| GPT-4.1 mini, v4 | 21/21 | Concrete symptom improved; some excerpts and attribution still drifted. |
| GPT-4.1, v4 | 21/21 | Better grounded details, but one response attributed helpful support to a review that did not mention support. |

| GPT-4.1, v5 | 21/21 | Quotation validation passed; manual comparison of the three complaint answers confirmed their cited source excerpts. |

The final implementation adds validation of quoted excerpts against the cited original text. Final release results follow in the repository README and the corresponding versioned JSON report. Earlier failed attempts are preserved intentionally, not relabeled as successful.
