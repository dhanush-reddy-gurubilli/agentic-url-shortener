package com.agentic.orchestrator.api;

import com.agentic.orchestrator.api.ApiDtos.RunView;
import com.agentic.orchestrator.domain.NotFoundException;
import com.agentic.orchestrator.domain.Records.Run;
import com.agentic.orchestrator.engine.RunService;
import com.agentic.orchestrator.scenario.ScenarioAutopilot;
import com.agentic.orchestrator.scenario.ScenarioCatalog;
import com.agentic.orchestrator.scenario.ScenarioConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scenarios")
@Tag(name = "Scenarios", description = "The greenfield, brownfield and ambiguous demo scenarios")
public class ScenarioController {

    public record ScenarioView(String name, String type, String description, String requirement, boolean hasFaults) {
        static ScenarioView of(ScenarioConfig s) {
            return new ScenarioView(s.name(), s.type(), s.description(), s.requirement(), !s.faults().isEmpty());
        }
    }

    private final ScenarioCatalog catalog;
    private final RunService runs;
    private final ScenarioAutopilot autopilot;
    private final HumanGuard guard;

    public ScenarioController(ScenarioCatalog catalog, RunService runs, ScenarioAutopilot autopilot, HumanGuard guard) {
        this.catalog = catalog;
        this.runs = runs;
        this.autopilot = autopilot;
        this.guard = guard;
    }

    @GetMapping
    public List<ScenarioView> list() {
        return catalog.list().stream().map(ScenarioView::of).toList();
    }

    @PostMapping("/{name}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start a scenario run. With autopilot=true a scripted human answers the checkpoints "
            + "(demo only; the audit trail names the actor 'autopilot:scripted-human')")
    public RunView run(@PathVariable String name, @RequestParam(defaultValue = "false") boolean autopilot,
                       @RequestHeader(value = "X-Approval-Key", required = false) String key) {
        guard.requireIfRunsCostMoney(key);
        ScenarioConfig scenario = catalog.find(name).orElseThrow(() -> new NotFoundException("Unknown scenario " + name));
        Run run = runs.start(scenario.requirement(), scenario.name(), scenario.tokenBudget());
        if (autopilot) {
            this.autopilot.attach(run.id(), scenario);
        }
        return RunView.of(run);
    }
}
