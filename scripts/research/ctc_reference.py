"""Research-only CTC Viterbi reference. Not an audio recognizer or app feature.
Input: per-frame normalized log probabilities, token ids (no blank), and exact
frame start timestamps (ms) supplied by a separately validated model adapter.
No inference, interpolation, network requests, or writes to audio.
"""
from dataclasses import dataclass
import math
import numpy as np


@dataclass(frozen=True)
class TokenSpan:
    token: int
    first_frame: int
    last_frame: int
    onset_ms: int
    peak_probability: float


def align(logp, tokens, frame_ms, blank=0, min_peak=0.5, max_cells=12_000_000):
    """Strict baseline: return all spans, or None if evidence is insufficient.

    min_peak is an uncalibrated research parameter, not a production threshold.
    A globally optimal path alone does not establish a correct song alignment.
    """
    logp = np.asarray(logp, dtype=np.float64)
    tokens = tuple(tokens)
    frame_ms = tuple(frame_ms)
    if logp.ndim != 2 or not tokens or not len(frame_ms):
        raise ValueError('empty or malformed input')
    t, v = logp.shape
    if len(frame_ms) != t or not 0 <= blank < v:
        raise ValueError('shape/blank mismatch')
    if any(not isinstance(i, (int, np.integer)) or i == blank or not 0 <= i < v for i in tokens):
        raise ValueError('invalid token')
    if any(not isinstance(i, (int, np.integer)) or i < 0 for i in frame_ms) or any(a >= b for a,b in zip(frame_ms, frame_ms[1:])):
        raise ValueError('invalid frame timeline')
    if np.any(np.isnan(logp)) or np.any(logp > 1e-8) or not np.allclose(np.exp(logp).sum(axis=1), 1, rtol=1e-5, atol=1e-6):
        raise ValueError('expected normalized log probabilities')
    if not 0 < min_peak <= 1 or max_cells <= 0:
        raise ValueError('invalid limits')
    s = 2*len(tokens)+1
    if t*s > max_cells:
        raise ValueError('trellis budget exceeded')
    if t < len(tokens)+sum(a==b for a,b in zip(tokens,tokens[1:])):
        return None
    labels = np.full(s, blank, dtype=np.int64)
    labels[1::2] = tokens
    skip = np.zeros(s, dtype=bool)
    skip[2:] = (labels[2:] != blank) & (labels[2:] != labels[:-2])
    prev = np.full(s, -np.inf)
    prev[0] = logp[0, blank]
    prev[1] = logp[0, tokens[0]]
    back = np.zeros((t,s), dtype=np.uint8)
    for i in range(1,t):
        stay = prev
        advance = np.r_[-np.inf, prev[:-1]]
        leap = np.r_[-np.inf, -np.inf, prev[:-2]]
        leap[~skip] = -np.inf
        choices = np.stack((stay,advance,leap))
        best = np.argmax(choices,axis=0)
        back[i] = best
        prev = choices[best,np.arange(s)] + logp[i,labels]
    state = s-1 if prev[-1] >= prev[-2] else s-2
    if not np.isfinite(prev[state]):
        return None
    path = np.empty(t,dtype=np.int64)
    for i in range(t-1,-1,-1):
        path[i] = state
        if i:
            state -= int(back[i,state])
    spans=[]
    for j, token in enumerate(tokens):
        idx = np.flatnonzero(path == 2*j+1)
        if not len(idx):
            return None
        peak = float(np.exp(np.max(logp[idx,token])))
        if peak < min_peak:
            return None
        spans.append(TokenSpan(int(token),int(idx[0]),int(idx[-1]),int(frame_ms[idx[0]]),peak))
    return spans


def _fixture(path, vocab=4):
    p = np.full((len(path),vocab),0.01/(vocab-1))
    p[np.arange(len(path)),path]=0.99
    return np.log(p), [i*20 for i in range(len(path))]


def selftest():
    # Leading/trailing instrumental spans and repeated vowels/characters.
    lp, ts = _fixture([0,0,1,1,0,1,0,0,0,2,2,0])
    r=align(lp,[1,1,2],ts)
    assert [x.onset_ms for x in r] == [40,100,180]
    # Repeated symbols cannot collapse to one token without an intervening blank.
    lp, ts = _fixture([1,1]); assert align(lp,[1,1],ts) is None
    # Wrong lyric and blank-only recording are not accepted by this baseline.
    lp,ts=_fixture([0,1,0,2,0]); assert align(lp,[3],ts) is None
    lp,ts=_fixture([0,0,0,0]); assert align(lp,[1],ts) is None
    # Reject unknown or negative timestamps and oversized trellis.
    for fn in [lambda: align(lp,[1],[-1,20,40,60]),lambda:align(lp,[1],ts,max_cells=1),lambda:align(lp,[0],ts)]:
        try: fn()
        except ValueError: pass
        else: raise AssertionError('invalid input accepted')
    print('CTC synthetic self-test: 7/7 PASS (no acoustic model or real-song evidence)')

if __name__=='__main__':
    selftest()
