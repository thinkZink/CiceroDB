package api;

import data.CSVConnector;
import org.springframework.web.bind.annotation.*;
import planning.PlanningManager;
import planning.PlanningResult;
import planning.VoicePlanner;
import planning.config.Config;
import planning.elements.TupleCollection;
import planning.planners.greedy.FantomGreedyPlanner;
import planning.planners.hybrid.HybridPlanner;
import planning.planners.hybrid.TupleCoveringPruner;
import planning.planners.linear.LinearProgrammingPlanner;
import planning.planners.naive.NaiveVoicePlanner;

import java.util.concurrent.atomic.AtomicLong;

@RestController
public class TestController {
    private final AtomicLong counter = new AtomicLong();
    private final PlanningManager planningManager = new PlanningManager();
    private final CSVConnector csvConnector = new CSVConnector();

    @RequestMapping(value = "/test", method = RequestMethod.POST)
    public TestResult newTest(@RequestBody TestInstance testInstance) throws Exception {
        VoicePlanner planner;
        switch (testInstance.algorithm) {
            case "naive":
                planner = new NaiveVoicePlanner();
                break;
            case "hybrid":
                planner = new HybridPlanner(new TupleCoveringPruner(10));
                break;
            case "fantom-greedy":
                planner = new FantomGreedyPlanner();
                break;
            case "linear":
                planner = new LinearProgrammingPlanner();
                break;
            default:
                throw new Exception("Invalid/unsupported algorithm");
        }

        TupleCollection tupleCollection = csvConnector.buildTupleCollectionFromCSV(testInstance.csvBody, testInstance.csvHeader);
        PlanningResult result = planningManager.buildPlan(planner, tupleCollection, new Config());
        return new TestResult(counter.incrementAndGet(), result);
    }

}