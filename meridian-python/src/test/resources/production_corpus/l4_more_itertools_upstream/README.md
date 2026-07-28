# L4 corpus — upstream more-itertools bodies

Escalation from L3: **real algorithm bodies** copied from
[more-itertools v10.7.0](https://github.com/more-itertools/more-itertools)
(`recipes.py` / `more.py`), annotations stripped.

`take`/`nth` keep upstream `islice` control flow; `quantify`/`dotproduct` use
equivalent explicit loops (same math as upstream `sum(map(...))`) so annotate→mypyc
stays sound. Bench wrappers return JSON-comparable scalars.

## Claim boundary

- Upstream more-itertools v10.7.0 algorithm bodies (MIT), selected.
- Not the full `more.py` (~5000 lines).
- May strip known-wrong Meridian `-> list[…]` return annotations before mypyc.

## Gates

Same four gates as L2/L3. Expect more modest speedups than rewritten list corpora.
