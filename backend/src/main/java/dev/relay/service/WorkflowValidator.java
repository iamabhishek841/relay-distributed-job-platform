package dev.relay.service;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class WorkflowValidator {

    public void validate(Map<String, List<String>> dependencies) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();

        for (String node : dependencies.keySet()) {
            indegree.putIfAbsent(node, 0);
            outgoing.putIfAbsent(node, new ArrayList<>());
        }

        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
            for (String parent : entry.getValue()) {
                if (!dependencies.containsKey(parent)) {
                    throw new IllegalArgumentException("Unknown dependency '" + parent + "' for node '" + entry.getKey() + "'");
                }
                outgoing.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(entry.getKey());
                indegree.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });

        int visited = 0;
        while (!ready.isEmpty()) {
            String node = ready.removeFirst();
            visited++;
            for (String child : outgoing.getOrDefault(node, List.of())) {
                int newDegree = indegree.merge(child, -1, Integer::sum);
                if (newDegree == 0) {
                    ready.addLast(child);
                }
            }
        }

        if (visited != dependencies.size()) {
            throw new IllegalArgumentException("Workflow contains a cycle");
        }
    }
}
