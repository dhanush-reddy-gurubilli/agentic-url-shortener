package com.agentic.orchestrator.gate;

import com.agentic.orchestrator.policy.PolicyEngine;
import org.springframework.stereotype.Component;

/** Runs every security, compliance and change-control rule over the patch before anything is applied. */
@Component
public class PatchPolicyGate implements Gate {

    private final PolicyEngine policy;

    public PatchPolicyGate(PolicyEngine policy) {
        this.policy = policy;
    }

    @Override
    public String name() {
        return "patch-policy";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        return GateResult.violations(policy.evaluate(ctx.changes()));
    }
}
