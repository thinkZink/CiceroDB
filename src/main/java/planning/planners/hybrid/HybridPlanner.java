package planning.planners.hybrid;

import ilog.concert.IloException;
import ilog.concert.IloIntVar;
import ilog.concert.IloLinearIntExpr;
import ilog.concert.IloLinearNumExpr;
import ilog.cplex.IloCplex;
import planning.VoiceOutputPlan;
import planning.config.Config;
import planning.elements.*;
import planning.planners.naive.NaiveVoicePlanner;

import java.util.*;

/**
 */
public class HybridPlanner extends NaiveVoicePlanner {
    private ContextPruner contextPruner;

    public HybridPlanner(ContextPruner contextPruner) {
        this.contextPruner = contextPruner;
    }

    @Override
    public VoiceOutputPlan plan(TupleCollection tupleCollection, Config config) {
        ArrayList<Context> contextCandidates = generateContextCandidates(tupleCollection, config);
        VoiceOutputPlan plan = null;

        try {
            IloCplex cplex = new IloCplex();

            int contextCount = contextCandidates.size();

            IloLinearNumExpr totalCost = cplex.linearNumExpr();
            IloIntVar[][] w = new IloIntVar[contextCount][];
            IloIntVar[] g = cplex.intVarArray(contextCount, 0, 1);

            for (int c = 0; c < contextCount; c++) {
                w[c] = cplex.intVarArray(tupleCollection.tupleCount(), 0, 1);
            }

            for (int c = 0; c < contextCount; c++) {
                Context context = contextCandidates.get(c);
                double contextCost = Scope.contextOverheadCost(tupleCollection.getTuplesClassName())
                        + context.toSpeechText(true).length();
                totalCost.addTerm(contextCost, g[c]);
            }

            for (int t = 0; t < tupleCollection.tupleCount(); t++) {
                Tuple tuple = tupleCollection.getTuple(t);
                int tWithoutContext = tuple.toSpeechText(true).length();
                for (int c = 0; c < contextCount; c++) {
                    Context context = contextCandidates.get(c);
                    if (context.matches(tuple)) {
                        int tWithContext = tuple.toSpeechText(context, true).length();
                        int savings = tWithoutContext - tWithContext;
                        totalCost.addTerm(-savings, w[c][t]);
                    } else {
                        cplex.addEq(w[c][t], 0);
                    }
                }
            }

            for (int c = 0; c < contextCount; c++) {
                // a context is used only if at least one tuple is output in it
                for (int t = 0; t < tupleCollection.tupleCount(); t++) {
                    cplex.addGe(g[c], w[c][t]);
                }
            }

            // constraint: each tuple mapped to one context
            for (int t = 0; t < tupleCollection.tupleCount(); t++) {
                IloLinearIntExpr sumForT = cplex.linearIntExpr();
                for (int c = 0; c < contextCount; c++) {
                    sumForT.addTerm(1, w[c][t]);
                }
                cplex.addLe(sumForT, 1);
            }

            cplex.addMinimize(totalCost);

            cplex.solve();

            // parse CPLEX output
            HashMap<Integer, ArrayList<Tuple>> tupleBins = new HashMap<>();
            for (int c = 0; c < contextCount; c++) {
                if (cplex.getValue(g[c]) > 0.5) {
                    tupleBins.put(c, new ArrayList<>());
                }
            }

            ArrayList<Tuple> emptyContextTuples = new ArrayList<>();

            for (int t = 0; t < tupleCollection.tupleCount(); t++) {
                boolean matched = false;
                for (int c = 0; c < contextCount; c++) {
                    if (cplex.getValue(w[c][t]) > 0.5) {
                        tupleBins.get(c).add(tupleCollection.getTuple(t));
                        matched = true;
                    }
                }
                if (!matched) {
                    emptyContextTuples.add(tupleCollection.getTuple(t));
                }
            }

            ArrayList<Scope> scopes = new ArrayList<>();
            if (!emptyContextTuples.isEmpty()) {
                scopes.add(new Scope(null, emptyContextTuples, tupleCollection.getTuplesClassName()));
            }

            for (int c = 0; c < contextCandidates.size(); c++) {
                if (tupleBins.containsKey(c)) {
                    scopes.add(new Scope(contextCandidates.get(c), tupleBins.get(c), tupleCollection.getTuplesClassName()));
                }
            }

            plan = new VoiceOutputPlan(scopes);
        } catch (IloException e) {
            e.printStackTrace();
        }

        return plan;
    }

    private ArrayList<Context> generateContextCandidates(TupleCollection tupleCollection, Config config) {
        Map<Integer, Set<ValueDomain>> candidateAssignments = tupleCollection.candidateAssignments(config.getMaxAllowableCategoricalDomainSize(),
                config.getMaxAllowableNumericalDomainWidth());

        ArrayList<Context> result = new ArrayList<>();

        int k = 0;
        Collection<Context> kAssignmentContexts = new ArrayList<>();
        kAssignmentContexts.add(new Context());

        while (k < config.getMaxAllowableContextSize()) {
            Collection<Context> kPlusOneAssignmentContexts = new ArrayList<>();
            for (Context c : kAssignmentContexts) {
                ArrayList<Context> unfiltered = new ArrayList<>();
                for (int a = 1; a < tupleCollection.attributeCount(); a++) {
                    if (!c.isAttributeFixed(tupleCollection.attributeForIndex(a))) {
                        for (ValueDomain d : candidateAssignments.get(a)) {
                            Context newContext = new Context(c);
                            newContext.addDomainAssignment(d);
                            unfiltered.add(newContext);
                        }
                    }
                }
                kPlusOneAssignmentContexts = contextPruner.prune(unfiltered, tupleCollection);
            }
            result.addAll(kPlusOneAssignmentContexts);
            kAssignmentContexts = kPlusOneAssignmentContexts;
            k++;
        }

        return result;
    }

    public void setContextPruner(ContextPruner contextPruner) {
        this.contextPruner = contextPruner;
    }

    @Override
    public String getPlannerIdentifier() {
        return "hybrid-" + contextPruner.getName();
    }

}
