package ro.distr2pl.app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ro.distr2pl.app.monitoring.TransactionMonitorService;
import ro.distr2pl.app.service.SimulationService;
import ro.distr2pl.app.transaction.TwoPhaseCommitJournal;

import java.util.List;
import java.util.Map;

/**
 * REST Controller for real-time monitoring of Distributed Transactions,
 * Strict 2PL Locks, Wait-For Graph, Deadlocks, and Concurrency Simulations.
 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final TransactionMonitorService monitorService;
    private final SimulationService simulationService;
    private final TwoPhaseCommitJournal journal;

    public MonitoringController(TransactionMonitorService monitorService,
                                SimulationService simulationService,
                                TwoPhaseCommitJournal journal) {
        this.monitorService = monitorService;
        this.simulationService = simulationService;
        this.journal = journal;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot() {
        return ResponseEntity.ok(monitorService.getFullMonitoringSnapshot());
    }

    @PostMapping("/simulate/race")
    public ResponseEntity<Map<String, Object>> simulateLastItemRace(
            @RequestParam(defaultValue = "4") int productId,
            @RequestParam(defaultValue = "1") int userId1,
            @RequestParam(defaultValue = "2") int userId2) throws Exception {
        return ResponseEntity.ok(simulationService.simulateLastProductRace(productId, userId1, userId2));
    }

    @PostMapping("/simulate/deadlock")
    public ResponseEntity<Map<String, Object>> simulateDeadlock(
            @RequestParam(defaultValue = "1") int productId1,
            @RequestParam(defaultValue = "2") int productId2) throws Exception {
        return ResponseEntity.ok(simulationService.simulateDeadlock(productId1, productId2));
    }

    @PostMapping("/simulate/rollback")
    public ResponseEntity<Map<String, Object>> simulateRollback(
            @RequestParam(defaultValue = "1") int userId,
            @RequestParam(defaultValue = "1") int productId) throws Exception {
        return ResponseEntity.ok(simulationService.simulateDistributedRollback(userId, productId));
    }

    @PostMapping("/recover-2pc")
    public ResponseEntity<List<String>> triggerRecovery() {
        return ResponseEntity.ok(journal.recoverInDoubtTransactions());
    }
}

