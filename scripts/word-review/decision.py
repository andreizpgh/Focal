"""Development-only training and threshold selection for ranking manual review suggestions."""
import math

FEATURES = ("initial_probability", "initial_ratio", "clipped_sequence_delta_div8", "absolute_shift_div2",
            "word_split", "nearby_confirmation", "relative_character_edit_distance")


def fit(samples):
    import numpy as np
    from scipy.optimize import minimize
    from scipy.special import logsumexp

    candidates = [option for _, _, _, options in samples for option in options]
    x = np.asarray([[1, *features] for features, _ in candidates])
    y = np.sign([change for _, change in candidates]).astype(int) + 1  # helped, tied, harmed
    targets = np.eye(3)[y]

    def objective(flat):
        weights = flat.reshape(x.shape[1], 3)
        scores = x @ weights
        logp = scores - logsumexp(scores, axis=1, keepdims=True)
        loss = -(targets * logp).sum() + .5 * (weights[1:] ** 2).sum()
        gradient = x.T @ (np.exp(logp) - targets)
        gradient[1:] += weights[1:]
        return loss, gradient.ravel()

    result = minimize(objective, np.zeros(x.shape[1] * 3), jac=True, method="L-BFGS-B")
    if not result.success:
        raise ValueError("Decision model failed to converge")
    return result.x.reshape(x.shape[1], 3)


def first_choices(samples, weights):
    import numpy as np

    choices = []
    for note, language, _, options in samples:
        if not options:
            continue
        features = np.asarray([[1, *values] for values, _ in options])
        logits = features @ weights
        p = np.exp(logits - logits.max(axis=1, keepdims=True))
        utility = (p[:, 0] - p[:, 2]) / p.sum(axis=1)
        i = int(utility.argmax())
        choices.append((float(utility[i]), note, language, options[i][1]))
    return choices


def counts(ranked, threshold):
    accepted = [r for r in ranked if r[0] >= threshold]
    return dict(offered=len(accepted), recordings=len({r[1] for r in accepted}),
                languages=len({r[2] for r in accepted}), helpful=sum(r[3] < 0 for r in accepted),
                tied=sum(r[3] == 0 for r in accepted), harmful=sum(r[3] > 0 for r in accepted),
                word_error_delta=sum(r[3] for r in accepted))


def decision_screen(samples):
    dev = [r for r in samples if r[2] == "dev"]
    holdout = [r for r in samples if r[2] == "holdout"]
    out_of_fold = []
    for lang in sorted({r[1] for r in dev}):
        weights = fit([r for r in dev if r[1] != lang])
        out_of_fold.extend(first_choices([r for r in dev if r[1] == lang], weights))
    out_of_fold.sort(reverse=True)
    final = fit(dev)
    test = first_choices(holdout, final)
    threshold = out_of_fold[min(len(out_of_fold), max(1, math.floor(.10 * len(dev)))) - 1][0]
    # Fix the confidence gate on development languages before applying it to held-out audio.
    gate = None
    for candidate in sorted({r[0] for r in out_of_fold}):
        c = counts(out_of_fold, candidate)
        if (c["offered"] >= 8 and c["languages"] >= 3 and
                c["helpful"] >= .6 * c["offered"] and c["harmful"] <= .1 * c["offered"]):
            gate = candidate
            break
    return dict(features=FEATURES, class_order=["helpful", "tie", "harmful"], coefficients=final.tolist(),
                development_flags=len(dev), holdout_flags=len(holdout),
                development_training_candidates=sum(len(r[3]) for r in dev),
                ten_percent_budget=dict(threshold=threshold, development_out_of_fold=counts(out_of_fold, threshold),
                                        holdout=counts(test, threshold)),
                high_precision_gate=None if gate is None else dict(
                    threshold=gate, development_out_of_fold=counts(out_of_fold, gate), holdout=counts(test, gate)))
