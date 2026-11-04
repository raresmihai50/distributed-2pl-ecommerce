function switchTab(tabName) {
    document.querySelectorAll('.tab-section').forEach(s => s.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    document.getElementById('tab-' + tabName).classList.add('active');
    const buttons = document.querySelectorAll('.nav-btn');
    const mapping = ['dashboard', 'products', 'inventory', 'orders', 'payments', 'transactions', 'locks', 'deadlocks'];
    const idx = mapping.indexOf(tabName);
    if (idx >= 0 && buttons[idx]) {
        buttons[idx].classList.add('active');
    }
    refreshAll();
}

function showBanner(msg, isError = false) {
    const el = document.getElementById('notification-banner');
    el.textContent = msg;
    el.className = 'notification ' + (isError ? 'error' : 'success');
    setTimeout(() => el.classList.add('hidden'), 7000);
}

async function apiCall(url, options = {}) {
    const res = await fetch(url, {
        headers: { 'Content-Type': 'application/json' },
        ...options
    });
    const data = await res.json();
    if (!res.ok) {
        throw new Error(data.message || JSON.stringify(data));
    }
    return data;
}

async function refreshAll() {
    try {
        const [users, products, inventory, orders, payments, monitor] = await Promise.all([
            apiCall('/api/users'),
            apiCall('/api/products'),
            apiCall('/api/inventory'),
            apiCall('/api/orders'),
            apiCall('/api/payments'),
            apiCall('/api/monitoring/snapshot')
        ]);

        renderUsers(users);
        renderProducts(products);
        renderInventory(inventory);
        renderOrders(orders);
        renderPayments(payments);
        renderMonitor(monitor);
    } catch (e) {
        console.error('Refresh error:', e);
    }
}

function renderUsers(users) {
    const tbody = document.getElementById('users-tbody');
    tbody.innerHTML = users.map(u => `
        <tr>
            <td>#${u.id}</td>
            <td><strong>${u.username}</strong></td>
            <td>${u.fullName}</td>
            <td>${u.email}</td>
            <td>$${Number(u.balance).toFixed(2)}</td>
        </tr>
    `).join('');
}

function renderProducts(products) {
    const tbody = document.getElementById('products-tbody');
    tbody.innerHTML = products.map(p => `
        <tr>
            <td>#${p.id}</td>
            <td><strong>${p.name}</strong><br><small style="color:#94a3b8">${p.description || ''}</small></td>
            <td>${p.category}</td>
            <td>$${Number(p.price).toFixed(2)}</td>
            <td><span class="badge badge-COMMITTED">${p.availableQuantity} units</span></td>
            <td>
                <button class="btn btn-sm btn-outline" onclick="promptUpdatePrice(${p.id}, ${p.price})">Edit Price</button>
            </td>
        </tr>
    `).join('');
}

function renderInventory(inventory) {
    const tbody = document.getElementById('inventory-tbody');
    tbody.innerHTML = inventory.map(i => `
        <tr>
            <td>#${i.productId}</td>
            <td>${i.productName || 'Product #' + i.productId}</td>
            <td><strong>${i.quantity}</strong></td>
            <td>${i.updatedAt ? i.updatedAt.replace('T', ' ').substring(0, 19) : '-'}</td>
            <td>
                <button class="btn btn-sm btn-primary" onclick="inspectStock(${i.productId})">S-Lock Check</button>
            </td>
        </tr>
    `).join('');
}

function renderOrders(orders) {
    const tbody = document.getElementById('orders-tbody');
    tbody.innerHTML = orders.map(o => {
        const itemsText = (o.items || []).map(i => `Prod #${i.productId} x${i.quantity} ($${i.subtotal})`).join(', ');
        return `
            <tr>
                <td>#${o.id}</td>
                <td>User #${o.userId}</td>
                <td>$${Number(o.totalAmount).toFixed(2)}</td>
                <td><span class="badge badge-${o.status}">${o.status}</span></td>
                <td>${itemsText || '-'}</td>
                <td>
                    <button class="btn btn-sm btn-success" onclick="payOrder(${o.id})">Pay (Op #6)</button>
                    <button class="btn btn-sm btn-warning" onclick="cancelOrder(${o.id})">Cancel (Op #7)</button>
                    <button class="btn btn-sm btn-danger" onclick="refundOrder(${o.id})">Refund (Op #8)</button>
                </td>
            </tr>
        `;
    }).join('');
}

function renderPayments(payments) {
    const tbody = document.getElementById('payments-tbody');
    tbody.innerHTML = payments.map(p => `
        <tr>
            <td>#${p.id}</td>
            <td>Order #${p.orderId}</td>
            <td>User #${p.userId}</td>
            <td>$${Number(p.amount).toFixed(2)}</td>
            <td>${p.paymentMethod}</td>
            <td><span class="badge badge-${p.status}">${p.status}</span></td>
            <td><code>${p.transactionId || '-'}</code></td>
            <td>${p.createdAt ? p.createdAt.replace('T', ' ').substring(0, 19) : '-'}</td>
        </tr>
    `).join('');
}

function renderMonitor(m) {
    document.getElementById('kpi-active').textContent = m.activeTransactionsCount;
    document.getElementById('kpi-committed').textContent = m.committedTransactionsCount;
    document.getElementById('kpi-aborted').textContent = m.abortedTransactionsCount;
    document.getElementById('kpi-deadlocks').textContent = m.deadlocksDetectedCount;

    // Transactions list
    const txList = document.getElementById('transactions-list');
    const txs = m.recentTransactions || [];
    txList.innerHTML = txs.map(tx => {
        const sqlText = (tx.sqlOperations || []).map(op =>
            `#${op.sequenceNumber} [${op.database}] ${op.operationType} ${op.tableName} (id=${op.recordId}): ${op.sql}`
        ).join('\n');
        const undoText = (tx.undoLog && tx.undoLog.entries ? tx.undoLog.entries : []).map(u =>
            `Undo #${u.operationOrder} [${u.database}.${u.tableName} id=${u.recordId}]: ${u.oldValue} -> ${u.newValue} | Inverse: ${u.inverseSql}`
        ).join('\n');
        const locksText = (tx.acquiredLocks || []).join(', ');
        return `
            <div class="tx-card">
                <div class="tx-header">
                    <div>
                        <strong>${tx.transactionId}</strong> &mdash; <span>${tx.operationName}</span>
                        <span class="badge badge-${tx.status}">${tx.status}</span>
                        <span class="badge badge-ACTIVE">2PC: ${tx.phase2pc}</span>
                    </div>
                    <small>${tx.durationMs} ms | PG Prepared: ${tx.postgresPrepared} | MySQL Prepared: ${tx.mysqlPrepared}</small>
                </div>
                <div><small><strong>Strict 2PL Locks Held:</strong> <code>${locksText || 'None'}</code></small></div>
                ${tx.errorMessage ? `<div style="color:#f87171;font-size:12px;margin-top:4px;"><strong>Abort Reason:</strong> ${tx.errorMessage}</div>` : ''}
                <div class="tx-details-grid">
                    <div>
                        <small>Executed SQL Operations (PostgreSQL & MySQL):</small>
                        <div class="code-box">${sqlText || 'No SQL executed'}</div>
                    </div>
                    <div>
                        <small>Undo Log Before-Images & Inverse SQL:</small>
                        <div class="code-box">${undoText || 'Read-only (no UndoLog entries)'}</div>
                    </div>
                </div>
            </div>
        `;
    }).join('') || '<div class="card">No distributed transactions executed yet.</div>';

    // Active Locks
    const locksTbody = document.getElementById('active-locks-tbody');
    locksTbody.innerHTML = (m.activeLocks || []).map(l => `
        <tr>
            <td><code>${l.resourceId}</code></td>
            <td><span class="badge badge-${l.lockType}">${l.lockType}</span></td>
            <td><code>${(l.owners || []).join(', ')}</code></td>
            <td>${l.grantedAt || '-'}</td>
        </tr>
    `).join('') || '<tr><td colspan="4">No locks currently held (all completed transactions released locks under Strict 2PL).</td></tr>';

    // Waiting Lock Requests
    const waitTbody = document.getElementById('waiting-locks-tbody');
    waitTbody.innerHTML = (m.waitingLockRequests || []).map(w => `
        <tr>
            <td><code>${w.resourceId}</code></td>
            <td><code>${w.transactionId}</code></td>
            <td><span class="badge badge-${w.requestedType}">${w.requestedType}</span></td>
            <td>${w.upgradeRequest ? 'YES (S->X)' : 'NO'}</td>
            <td>${w.requestedAt || '-'}</td>
        </tr>
    `).join('') || '<tr><td colspan="5">No transactions currently waiting for locks.</td></tr>';

    // Wait-For Graph
    const wfg = m.waitForGraph || {};
    const wfgKeys = Object.keys(wfg);
    document.getElementById('wfg-container').textContent = wfgKeys.length
        ? wfgKeys.map(k => `${k} -> [${wfg[k].join(', ')}]`).join('\n')
        : 'Graph is currently acyclic (no active wait-for edges).';

    // Recovery reports
    const rec = m.recoveryReports || [];
    document.getElementById('recovery-container').textContent = rec.length
        ? rec.join('\n')
        : '2PC Coordinator Journal clean. All transactions resolved.';

    // Deadlocks table
    const dlTbody = document.getElementById('deadlocks-tbody');
    dlTbody.innerHTML = (m.deadlockEvents || []).map(d => `
        <tr>
            <td><code>${d.id}</code></td>
            <td>${d.detectedAt ? d.detectedAt.replace('T', ' ').substring(0, 19) : '-'}</td>
            <td><strong style="color:#fbbf24">${d.cycleFormatted}</strong></td>
            <td><code>${d.resourceInvolved}</code></td>
            <td><span class="badge badge-ABORTED">${d.victimTransactionId}</span></td>
            <td>${d.selectionStrategy}</td>
            <td>${d.resolution}</td>
        </tr>
    `).join('') || '<tr><td colspan="7">No deadlocks detected yet. Click "Trigger Deadlock Simulation Now" to test!</td></tr>';
}

async function handleCreateUser(e) {
    e.preventDefault();
    try {
        const user = await apiCall('/api/users', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('user-username').value,
                email: document.getElementById('user-email').value,
                fullName: document.getElementById('user-fullname').value,
                balance: parseFloat(document.getElementById('user-balance').value)
            })
        });
        showBanner(`Created User #${user.id} (${user.username}) in PostgreSQL!`);
        e.target.reset();
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function handleCreateProduct(e) {
    e.preventDefault();
    try {
        const prod = await apiCall('/api/products', {
            method: 'POST',
            body: JSON.stringify({
                name: document.getElementById('prod-name').value,
                description: document.getElementById('prod-desc').value,
                category: document.getElementById('prod-cat').value,
                price: parseFloat(document.getElementById('prod-price').value),
                initialQuantity: parseInt(document.getElementById('prod-qty').value, 10)
            })
        });
        showBanner(`Added Product #${prod.id} (${prod.name}) with stock=${prod.availableQuantity}!`);
        e.target.reset();
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function promptUpdatePrice(productId, currentPrice) {
    const input = prompt(`Enter new price for Product #${productId}:`, currentPrice);
    if (!input) return;
    try {
        const updated = await apiCall(`/api/products/${productId}/price`, {
            method: 'PUT',
            body: JSON.stringify({ newPrice: parseFloat(input) })
        });
        showBanner(`Updated Product #${updated.id} price to $${updated.price} under Strict 2PL X-Lock!`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function handleCheckStock() {
    const id = document.getElementById('check-stock-id').value;
    await inspectStock(id);
}

async function inspectStock(productId) {
    try {
        const inv = await apiCall(`/api/inventory/${productId}`);
        const box = document.getElementById('stock-check-result');
        box.classList.remove('hidden');
        box.textContent = JSON.stringify(inv, null, 2);
        showBanner(`Checked stock for Product #${productId} (${inv.productName}): ${inv.quantity} available.`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function handlePlaceOrder(e) {
    e.preventDefault();
    try {
        const order = await apiCall('/api/orders', {
            method: 'POST',
            body: JSON.stringify({
                userId: parseInt(document.getElementById('order-userid').value, 10),
                paymentMethod: document.getElementById('order-paymethod').value,
                items: [{
                    productId: parseInt(document.getElementById('order-productid').value, 10),
                    quantity: parseInt(document.getElementById('order-qty').value, 10)
                }],
                simulateFailureAfterWrites: document.getElementById('order-sim-fail').checked,
                simulateMysqlPrepareFailure: document.getElementById('order-sim-2pc').checked
            })
        });
        showBanner(`Distributed Order #${order.id} COMMITTED across PostgreSQL & MySQL via 2PC!`);
        refreshAll();
    } catch (err) {
        showBanner(`Distributed Transaction ABORTED & Rolled Back via UndoLog: ${err.message}`, true);
        refreshAll();
    }
}

async function payOrder(orderId) {
    try {
        const payment = await apiCall(`/api/orders/${orderId}/pay`, { method: 'POST' });
        showBanner(`Order #${orderId} PAID (Payment #${payment.id}) across PostgreSQL & MySQL!`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
        refreshAll();
    }
}

async function cancelOrder(orderId) {
    try {
        await apiCall(`/api/orders/${orderId}/cancel`, { method: 'POST' });
        showBanner(`Order #${orderId} CANCELLED and stock restored in PostgreSQL!`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
        refreshAll();
    }
}

async function refundOrder(orderId) {
    try {
        await apiCall(`/api/orders/${orderId}/refund`, { method: 'POST' });
        showBanner(`Order #${orderId} REFUNDED to user balance and products restocked!`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
        refreshAll();
    }
}

async function runRaceSimulation() {
    try {
        const res = await apiCall('/api/monitoring/simulate/race', { method: 'POST' });
        const box = document.getElementById('simulation-output');
        box.classList.remove('hidden');
        box.textContent = JSON.stringify(res, null, 2);
        showBanner(`Last-Item Race Completed: ${res.committedTransactions} COMMITTED, ${res.abortedTransactions} ABORTED, Final Stock = ${res.finalStock}`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function runDeadlockSimulation() {
    try {
        const res = await apiCall('/api/monitoring/simulate/deadlock', { method: 'POST' });
        const box = document.getElementById('simulation-output');
        box.classList.remove('hidden');
        box.textContent = JSON.stringify(res, null, 2);
        showBanner(`Deadlock Detected & Resolved! Survivor committed: ${res.committedTransactions}, Victim rolled back: ${res.deadlockAbortedTransactions}`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

async function runRollbackSimulation() {
    try {
        const res = await apiCall('/api/monitoring/simulate/rollback', { method: 'POST' });
        const box = document.getElementById('simulation-output');
        box.classList.remove('hidden');
        box.textContent = JSON.stringify(res, null, 2);
        showBanner(`Distributed Rollback Verified! Restored cleanly in PostgreSQL & MySQL = ${res.restoredCleanly}`);
        refreshAll();
    } catch (err) {
        showBanner(err.message, true);
    }
}

window.addEventListener('DOMContentLoaded', () => {
    refreshAll();
    setInterval(refreshAll, 4000);
});

