// Sentinel Dashboard - app.js

(function() {
    'use strict';

    // --- Stats polling ---
    function fetchStats() {
        fetch('/api/stats')
            .then(r => r.json())
            .then(data => {
                document.getElementById('statFps').textContent = data.fps.toFixed(1);
                document.getElementById('statActive').textContent = data.activeCount;
                document.getElementById('statInference').textContent = data.inferenceTime;
                document.getElementById('statEvents').textContent = data.todayEvents;
                document.getElementById('statusText').textContent = 'Monitoring';
            })
            .catch(() => {
                document.getElementById('statusText').textContent = 'Disconnected';
            });
    }
    setInterval(fetchStats, 1000);
    fetchStats();

    // --- SSE event stream ---
    let eventListData = [];
    const MAX_EVENTS = 50;

    function connectSSE() {
        const evtSource = new EventSource('/api/events/stream');

        evtSource.onmessage = function(e) {
            try {
                const event = JSON.parse(e.data);
                eventListData.unshift(event);
                if (eventListData.length > MAX_EVENTS) eventListData.pop();
                renderEvents();
            } catch (err) { /* skip */ }
        };

        evtSource.onerror = function() {
            evtSource.close();
            setTimeout(connectSSE, 3000);
        };
    }
    connectSSE();

    // Load initial events
    fetch('/api/events?limit=50')
        .then(r => r.json())
        .then(events => {
            eventListData = events.map(e => ({
                id: e.id,
                type: e.type,
                label: getEventLabel(e),
                confidence: e.confidence || 0,
                duration: e.duration || 0,
                timestamp: e.timestamp,
                snapshotPath: e.snapshotPath
            }));
            renderEvents();
        })
        .catch(() => {});

    function getEventLabel(e) {
        const labels = {
            'PERSON_ENTERED': 'Person entered',
            'PERSON_EXITED': 'Person exited',
            'EMPLOYEE_ARRIVED': 'Employee: ' + (e.employeeId || 'Unknown'),
            'EMPLOYEE_DEPARTED': 'Employee departed',
            'VEHICLE_ENTERED': 'Vehicle entered' + (e.licensePlate ? ' (' + e.licensePlate + ')' : ''),
            'VEHICLE_EXITED': 'Vehicle exited',
            'UNKNOWN_FACE_DETECTED': 'Unknown face detected',
            'LOITERING_DETECTED': 'Loitering detected'
        };
        return labels[e.type] || e.type;
    }

    function getEventIcon(type) {
        if (!type) return { cls: 'system', icon: 'i' };
        const t = type.toUpperCase ? type.toUpperCase() : type;
        if (t.includes('PERSON') || t.includes('DETECTION_START') || t.includes('DETECTION_END'))
            return { cls: 'person', icon: '\u{1F464}' };
        if (t.includes('VEHICLE'))
            return { cls: 'vehicle', icon: '\u{1F697}' };
        if (t.includes('FACE') || t.includes('EMPLOYEE'))
            return { cls: 'face', icon: '\u{2714}' };
        if (t.includes('LOITERING'))
            return { cls: 'alert', icon: '\u{26A0}' };
        return { cls: 'system', icon: 'i' };
    }

    function relativeTime(ts) {
        const diff = Date.now() - ts;
        if (diff < 60000) return Math.floor(diff / 1000) + 's ago';
        if (diff < 3600000) return Math.floor(diff / 60000) + 'm ago';
        if (diff < 86400000) return Math.floor(diff / 3600000) + 'h ago';
        return new Date(ts).toLocaleString();
    }

    function renderEvents() {
        const list = document.getElementById('eventList');
        if (eventListData.length === 0) {
            list.innerHTML = '<li class="empty-state">Waiting for events...</li>';
            return;
        }

        list.innerHTML = eventListData.map(ev => {
            const icon = getEventIcon(ev.type);
            const label = ev.label || getEventLabel(ev);
            const thumb = ev.snapshotPath
                ? '<img class="event-thumb" src="/api/snapshot/' + ev.snapshotPath + '" alt="">'
                : '';
            return '<li class="event-item">' +
                '<div class="event-icon ' + icon.cls + '">' + icon.icon + '</div>' +
                '<div class="event-info">' +
                    '<div class="event-label">' + escapeHtml(label) + '</div>' +
                    '<div class="event-time">' + relativeTime(ev.timestamp) + '</div>' +
                '</div>' +
                thumb +
            '</li>';
        }).join('');
    }

    // Refresh relative times
    setInterval(renderEvents, 10000);

    // --- Tabs ---
    const tabs = document.querySelectorAll('.tab');
    const panes = document.querySelectorAll('.tab-pane');
    const loadedTabs = {};

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            const target = tab.dataset.tab;
            tabs.forEach(t => t.classList.remove('active'));
            panes.forEach(p => p.classList.remove('active'));
            tab.classList.add('active');
            document.getElementById('tab-' + target).classList.add('active');

            // Lazy load data
            if (!loadedTabs[target]) {
                loadedTabs[target] = true;
                if (target === 'employees') loadEmployees();
                if (target === 'vehicles') loadVehicles();
                if (target === 'attendance') loadAttendance();
            }
        });
    });

    function loadEmployees() {
        fetch('/api/employees')
            .then(r => r.json())
            .then(data => {
                const el = document.getElementById('employeesContent');
                if (data.length === 0) {
                    el.innerHTML = '<div class="empty-state">No employees registered</div>';
                    return;
                }
                el.innerHTML = '<table class="data-table"><thead><tr>' +
                    '<th>ID</th><th>Name</th><th>Department</th><th>Face</th>' +
                '</tr></thead><tbody>' +
                data.map(e =>
                    '<tr><td>' + escapeHtml(e.employeeId) + '</td>' +
                    '<td>' + escapeHtml(e.name) + '</td>' +
                    '<td>' + escapeHtml(e.department) + '</td>' +
                    '<td>' + (e.hasEmbedding
                        ? '<span class="badge badge-green">Enrolled</span>'
                        : '<span class="badge badge-orange">Pending</span>') + '</td></tr>'
                ).join('') + '</tbody></table>';
            })
            .catch(() => {
                document.getElementById('employeesContent').innerHTML =
                    '<div class="empty-state">Failed to load</div>';
            });
    }

    function loadVehicles() {
        fetch('/api/vehicles')
            .then(r => r.json())
            .then(data => {
                const el = document.getElementById('vehiclesContent');
                if (data.length === 0) {
                    el.innerHTML = '<div class="empty-state">No vehicles recorded</div>';
                    return;
                }
                el.innerHTML = '<table class="data-table"><thead><tr>' +
                    '<th>Plate</th><th>Type</th><th>Last Seen</th>' +
                '</tr></thead><tbody>' +
                data.map(v =>
                    '<tr><td><strong>' + escapeHtml(v.licensePlate) + '</strong></td>' +
                    '<td><span class="badge badge-blue">' + escapeHtml(v.vehicleType || 'Unknown') + '</span></td>' +
                    '<td>' + relativeTime(v.lastSeen) + '</td></tr>'
                ).join('') + '</tbody></table>';
            })
            .catch(() => {
                document.getElementById('vehiclesContent').innerHTML =
                    '<div class="empty-state">Failed to load</div>';
            });
    }

    function loadAttendance() {
        const today = new Date().toISOString().split('T')[0];
        fetch('/api/attendance?date=' + today)
            .then(r => r.json())
            .then(data => {
                const el = document.getElementById('attendanceContent');
                if (data.length === 0) {
                    el.innerHTML = '<div class="empty-state">No attendance records for today</div>';
                    return;
                }
                el.innerHTML = '<table class="data-table"><thead><tr>' +
                    '<th>Employee</th><th>Check In</th><th>Check Out</th><th>Duration</th>' +
                '</tr></thead><tbody>' +
                data.map(a => {
                    const checkIn = a.checkInTime ? new Date(a.checkInTime).toLocaleTimeString() : '--';
                    const checkOut = a.checkOutTime ? new Date(a.checkOutTime).toLocaleTimeString() : '--';
                    const dur = a.totalDuration > 0 ? formatDuration(a.totalDuration) : '--';
                    return '<tr><td>' + escapeHtml(a.employeeId) + '</td>' +
                        '<td>' + checkIn + '</td>' +
                        '<td>' + checkOut + '</td>' +
                        '<td>' + dur + '</td></tr>';
                }).join('') + '</tbody></table>';
            })
            .catch(() => {
                document.getElementById('attendanceContent').innerHTML =
                    '<div class="empty-state">Failed to load</div>';
            });
    }

    function formatDuration(ms) {
        const s = Math.floor(ms / 1000);
        if (s < 60) return s + 's';
        if (s < 3600) return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
        return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }
})();
