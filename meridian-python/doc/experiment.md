# GCP Converter E2E Performance Experiments

Pipeline: untyped Python → GCP type inference → mypyc compile → benchmark

- **bare×**: `mypyc(bare untyped) / CPython`
- **GCP×**: `mypyc(GCP-annotated) / CPython`
- **GCP/bare**: relative lift from GCP type annotations

All correctness checks pass (GCP output == bare output == CPython output).

---

## P0 + P1 + P2 + P3 + P4 + P5 Converters (18 tests, parallel, ~30 min)

| Converter          | bare× | GCP×   | GCP/bare | Key pattern                                          |
|--------------------|-------|--------|----------|------------------------------------------------------|
| assert_isinstance  | 0.95× | 16.49× |  17.36×  | `assert isinstance(x, int)` narrows param type       |
| aug_assign         | 0.89× |  7.28× |   8.15×  | `x += i` → `x = x + i`, int accumulation            |
| builtin_sorted     | 1.07× | 11.90× |  11.12×  | `max(int,int)→int`, `sorted(range(n))→list[int]`    |
| builtins           | 0.92× |  3.90× |   4.24×  | `len(lst)→int`, `abs(int)→int`, loop bounds typed    |
| list_method        | 1.16× |  2.44× |   2.11×  | `list.index()→int` enables typed inner loop          |
| method_call        | 1.18× |  3.93× |   3.32×  | `str.count/find()→int` unlocks int accumulation chain|
| default_params     | 1.52× | 20.63× |  13.57×  | `def f(x=0)` → `x: int` from default literal        |
| enumerate_zip      | 0.81× | 28.03× |  34.75×  | `enumerate(range(n))` → idx:int, val:int             |
| for_loop_var       | 0.81× | 16.79× |  20.81×  | `for x in lst` → `x: T` via ArrayAccessor            |
| fstring            | 0.83× | 18.69× |  22.51×  | `f"..."` → `str`, pipeline robustness + int hot loop |
| ifexp              | 0.91× | 22.74× |  24.89×  | `a if c else b` → TernaryExpression, int branches    |
| lambda             | 0.64× | 26.11× |  40.80×  | lambda defs + direct int arithmetic hot loop         |
| listcomp           | 0.98× |  4.43× |   4.54×  | `[i*i for i in range(n)]`, typed element             |
| match_case         | 0.99× | 30.32× |  30.73×  | `match i % 4: case 0: ...` typed integer dispatch    |
| named_expr         | 1.61× | 43.54× |  26.99×  | `i := i + 1` walrus, fully-typed int while loop      |
| starred            | 1.01× |  9.11× |   9.05×  | `first, *rest = lst` → `rest: list[int]`             |
| subscript          | 1.24× |  1.86× |   1.50×  | `lst[i]` → ArrayAccessor (str ops limit gain)        |
| tuple_unpack       | 0.88× | 18.77× |  21.41×  | `a, b = f()` → TupleUnpackNode, typed returns        |
| **AVERAGE**        | **1.04×** | **15.52×** | **14.98×** |                                               |

### Highlights
- **`named_expr`** (walrus `:=`) reaches **43.54×** — walrus in while conditions gives a fully-typed
  integer loop that mypyc compiles to tight C arithmetic.
- **`match_case`** reaches **30.32×** — single-value integer match is JIT-optimized by mypyc into
  a native switch-like dispatch.
- **`enumerate_zip`** reaches **28.03×** — GCP now special-cases `enumerate(range(n))` and
  `zip(range(n), ...)` to produce direct integer element types without list creation overhead.
- **`builtin_sorted`** `max(int, int)→int` enables **32.05×** on `max_of_range` loop.
- **`fstring`** P4-A safety-net: f-strings don't break GCP pipeline; `len(label)→int` inferred
  correctly, enabling **20.79×** on the same function.
- **`method_call`** (P5-A): `str.count()/find()→int` allows the type chain `c: int → total: int → → int` to complete, giving **4.78×** on `count_ones`.
- **`bare×` ≈ 1.0×** across the board — untyped mypyc rarely beats CPython; GCP annotations
  are the decisive factor.

---

## P0 converters

### AugAssign
| Function                | CPython ns | bare ns | GCP ns | GCP× |
|-------------------------|-----------|---------|--------|------|
| sum_range(1000)         |  54 724   |  74 496 |  4 562 | 12.0× |
| count_multiples(1000,7) |  78 502   | 112 552 |  4 308 | 18.2× |
| running_product(50)     |   5 387   |   7 335 |    311 | 17.3× |

### Tuple unpack
| Function                | CPython ns | bare ns | GCP ns | GCP× |
|-------------------------|-----------|---------|--------|------|
| sum_divmod(1000,7)      | 128 740   | 104 598 |  9 880 | 13.0× |
| sum_pair_products(1000) | 111 373   | 134 547 |  6 530 | 17.1× |
| sum_swap_steps(500)     | 138 753   | 117 097 | 88 344 |  1.6× |

---

## P2 converters

### NamedExpr (walrus)
| Function                | CPython ns | bare ns  | GCP ns | GCP× |
|-------------------------|-----------|---------|--------|------|
| sum_walrus_while(1000)  |  87 177   |  69 841 |  3 672 | 23.7× |
| count_walrus_if(10000)  | 953 371   |1284 956 | 33 276 | 28.7× |
| accumulate_walrus(1000) | 110 256   |  95 703 |  4 586 | 24.0× |

### Starred assignment
| Function                   | CPython ns | bare ns | GCP ns | GCP× |
|----------------------------|-----------|---------|--------|------|
| sum_after_split(1000)      | 285 428   | 233 432 | 46 885 |  6.1× |
| sum_tail_elements(1000)    | 216 777   | 236 587 | 27 852 |  7.8× |
| process_starred_loop(1000) | 220 895   | 210 327 | 26 500 |  8.3× |

---

## P3 converters

### Enumerate / Zip
| Function               | CPython ns | bare ns | GCP ns | GCP× |
|------------------------|-----------|---------|--------|------|
| sum_enumerate(1000)    |  61 399   |  73 395 |  2 708 | 22.7× |
| dot_zip(1000)          |  70 912   | 116 163 |  4 344 | 16.3× |
| cross_enumerate(1000)  |  69 345   |  92 861 |  2 787 | 24.9× |

### Assert isinstance
| Function                    | CPython ns | bare ns | GCP ns | GCP× |
|-----------------------------|-----------|---------|--------|------|
| guarded_sum(5, 1000)        |  54 123   |  99 354 |  5 260 | 10.3× |
| guarded_product(3, 500)     |  26 771   |  43 607 |  1 699 | 15.8× |
| guarded_accumulate(2, 1000) |  95 008   |  79 927 |  3 783 | 25.1× |

### Match / Case
| Function              | CPython ns | bare ns | GCP ns | GCP× |
|-----------------------|-----------|---------|--------|------|
| classify_and_sum(1000)|  112 584  | 147 706 |  6 184 | 18.2× |
| sum_even_match(1000)  |  104 209  | 153 343 |  6 342 | 16.4× |
| sum_mod3_match(1000)  |  139 947  |  95 575 |  3 466 | 40.4× |

---

---

## P4 converters

### Built-in return type inference
| Function                | CPython ns | bare ns  | GCP ns  | GCP× |
|-------------------------|-----------|---------|--------|------|
| sum_with_len(1000)      |  70 872   |  89 933 | 21 400 |  3.31× |
| count_positive(1000)    |  73 990   |  87 654 | 17 489 |  4.23× |
| sum_abs_values(1000)    | 102 193   |  90 756 | 24 542 |  4.16× |

### sorted / max / sum with range
| Function                | CPython ns | bare ns  | GCP ns  | GCP× |
|-------------------------|-----------|---------|--------|------|
| sum_sorted_range(1000)  |  77 021   |  87 328 | 28 319 |  2.72× |
| sum_directly(100000)    |1 489 987  |1 538 373|1 593 209|  0.94× |
| max_of_range(1000)      | 141 621   | 104 342 |  4 419 | 32.05× |

### F-string (P4-A safety net)
| Function                | CPython ns | bare ns  | GCP ns  | GCP× |
|-------------------------|-----------|---------|--------|------|
| format_sum(1000)        |  45 811   |  67 274 |  2 203 | 20.79× |
| count_labels(1000)      |  59 559   |  60 635 |  3 593 | 16.58× |

---

---

## P5 converters

### str method return type inference
| Function                | CPython ns | bare ns  | GCP ns  | GCP× |
|-------------------------|-----------|---------|--------|------|
| count_ones(10000)       | 2 425 316 | 1 766 169|  507 231|  4.78× |
| find_sum(10000)         | 2 117 563 | 1 838 840|  512 014|  4.14× |
| upper_len_sum(10000)    | 2 897 497 | 2 809 724| 1 006 051|  2.88× |

### list method return type inference
| Function                | CPython ns | bare ns  | GCP ns  | GCP× |
|-------------------------|-----------|---------|--------|------|
| index_guided_sum(1000)  |  83 829   |  51 517 | 22 108 |  3.79× |
| index_double_loop(1000) |  70 466   | 104 322 | 65 198 |  1.08× |

---

*Last updated: 2026-03-10. Tests run on macOS with Python 3.14, JUnit 5 parallel (4 threads).*
