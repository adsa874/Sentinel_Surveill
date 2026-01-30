// Sentinel PWA Application

class SentinelApp {
  constructor() {
    this.ws = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.pushSubscription = null;

    this.init();
  }

  async init() {
    await this.registerServiceWorker();
    this.setupWebSocket();
    this.setupPullToRefresh();
    this.setupNotifications();
    this.updateOnlineStatus();

    window.addEventListener('online', () => this.updateOnlineStatus());
    window.addEventListener('offline', () => this.updateOnlineStatus());
  }

  // Service Worker Registration
  async registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      try {
        const registration = await navigator.serviceWorker.register('/static/sw.js');
        console.log('ServiceWorker registered:', registration.scope);
        this.swRegistration = registration;
      } catch (error) {
        console.error('ServiceWorker registration failed:', error);
      }
    }
  }

  // WebSocket for real-time updates
  setupWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    try {
      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log('WebSocket connected');
        this.reconnectAttempts = 0;
        this.showToast('Connected', 'success');
      };

      this.ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        this.handleRealtimeEvent(data);
      };

      this.ws.onclose = () => {
        console.log('WebSocket disconnected');
        this.attemptReconnect();
      };

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error);
      };
    } catch (e) {
      console.log('WebSocket not available, using polling');
      this.setupPolling();
    }
  }

  attemptReconnect() {
    if (this.reconnectAttempts < this.maxReconnectAttempts) {
      this.reconnectAttempts++;
      const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 30000);
      console.log(`Reconnecting in ${delay}ms...`);
      setTimeout(() => this.setupWebSocket(), delay);
    }
  }

  setupPolling() {
    // Fallback polling every 10 seconds
    setInterval(() => this.fetchLatestEvents(), 10000);
  }

  async fetchLatestEvents() {
    try {
      const response = await fetch('/api/events?limit=10');
      const events = await response.json();
      this.updateEventsList(events);
    } catch (error) {
      console.error('Failed to fetch events:', error);
    }
  }

  handleRealtimeEvent(data) {
    switch (data.type) {
      case 'new_event':
        this.addEventToList(data.event);
        this.updateStats(data.stats);
        this.showEventNotification(data.event);
        break;
      case 'stats_update':
        this.updateStats(data.stats);
        break;
      case 'device_status':
        this.updateDeviceStatus(data.device);
        break;
    }
  }

  addEventToList(event) {
    const tbody = document.querySelector('.events-table tbody');
    if (!tbody) return;

    const row = document.createElement('tr');
    row.className = 'new-event-row';
    row.innerHTML = `
      <td>${this.formatTime(event.timestamp)}</td>
      <td><span class="event-badge event-${event.event_type.toLowerCase().replace(/_/g, '-')}">${event.event_type.replace(/_/g, ' ')}</span></td>
      <td>${event.employee_name || event.license_plate || '-'}</td>
      <td>${event.duration || '-'}</td>
    `;

    tbody.insertBefore(row, tbody.firstChild);

    // Remove old rows if too many
    while (tbody.children.length > 20) {
      tbody.removeChild(tbody.lastChild);
    }

    // Animate
    requestAnimationFrame(() => row.classList.add('visible'));
  }

  updateStats(stats) {
    if (!stats) return;

    const statElements = {
      'total_events': document.querySelector('[data-stat="total-events"]'),
      'people_detected': document.querySelector('[data-stat="people-detected"]'),
      'vehicles_detected': document.querySelector('[data-stat="vehicles-detected"]'),
      'employees_present': document.querySelector('[data-stat="employees-present"]'),
      'active_devices': document.querySelector('[data-stat="active-devices"]')
    };

    for (const [key, element] of Object.entries(statElements)) {
      if (element && stats[key] !== undefined) {
        const oldValue = parseInt(element.textContent);
        const newValue = stats[key];

        if (oldValue !== newValue) {
          element.textContent = newValue;
          element.classList.add('stat-updated');
          setTimeout(() => element.classList.remove('stat-updated'), 500);
        }
      }
    }
  }

  updateDeviceStatus(device) {
    const deviceCard = document.querySelector(`[data-device-id="${device.device_id}"]`);
    if (deviceCard) {
      const indicator = deviceCard.querySelector('.status-indicator');
      if (indicator) {
        indicator.className = `status-indicator ${device.is_online ? 'online' : 'offline'}`;
      }
    }
  }

  // Push Notifications
  async setupNotifications() {
    if (!('Notification' in window)) return;

    if (Notification.permission === 'default') {
      // Show prompt to enable notifications
      this.showNotificationPrompt();
    } else if (Notification.permission === 'granted') {
      await this.subscribeToPush();
    }
  }

  showNotificationPrompt() {
    const banner = document.createElement('div');
    banner.className = 'notification-banner';
    banner.innerHTML = `
      <p>Enable notifications for security alerts?</p>
      <div class="banner-actions">
        <button class="btn btn-primary" id="enableNotifications">Enable</button>
        <button class="btn btn-secondary" id="dismissNotifications">Not now</button>
      </div>
    `;
    document.body.appendChild(banner);

    document.getElementById('enableNotifications').addEventListener('click', async () => {
      const permission = await Notification.requestPermission();
      if (permission === 'granted') {
        await this.subscribeToPush();
      }
      banner.remove();
    });

    document.getElementById('dismissNotifications').addEventListener('click', () => {
      banner.remove();
    });
  }

  async subscribeToPush() {
    if (!this.swRegistration) return;

    try {
      // Get VAPID public key from server
      const response = await fetch('/api/push/vapid-public-key');
      const { publicKey } = await response.json();

      this.pushSubscription = await this.swRegistration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: this.urlBase64ToUint8Array(publicKey)
      });

      // Send subscription to server
      await fetch('/api/push/subscribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(this.pushSubscription)
      });

      console.log('Push subscription successful');
    } catch (error) {
      console.error('Push subscription failed:', error);
    }
  }

  showEventNotification(event) {
    if (Notification.permission !== 'granted') return;

    // Don't show for routine events
    const alertTypes = ['UNKNOWN_FACE_DETECTED', 'LOITERING_DETECTED'];
    if (!alertTypes.includes(event.event_type)) return;

    const title = event.event_type.replace(/_/g, ' ');
    const body = event.employee_name || event.license_plate || 'Security event detected';

    new Notification(title, {
      body,
      icon: '/static/icons/icon-192.png',
      tag: `event-${event.id}`
    });
  }

  // Pull to Refresh
  setupPullToRefresh() {
    let startY = 0;
    let pulling = false;
    const threshold = 80;

    document.addEventListener('touchstart', (e) => {
      if (window.scrollY === 0) {
        startY = e.touches[0].pageY;
        pulling = true;
      }
    }, { passive: true });

    document.addEventListener('touchmove', (e) => {
      if (!pulling) return;

      const currentY = e.touches[0].pageY;
      const diff = currentY - startY;

      if (diff > 0 && diff < threshold * 2) {
        const pullIndicator = document.querySelector('.pull-indicator');
        if (pullIndicator) {
          pullIndicator.style.transform = `translateY(${Math.min(diff, threshold)}px)`;
          pullIndicator.style.opacity = Math.min(diff / threshold, 1);
        }
      }
    }, { passive: true });

    document.addEventListener('touchend', (e) => {
      if (!pulling) return;
      pulling = false;

      const pullIndicator = document.querySelector('.pull-indicator');
      if (pullIndicator) {
        pullIndicator.style.transform = '';
        pullIndicator.style.opacity = '';
      }

      const endY = e.changedTouches[0].pageY;
      if (endY - startY > threshold) {
        this.refresh();
      }
    }, { passive: true });
  }

  async refresh() {
    this.showToast('Refreshing...', 'info');
    window.location.reload();
  }

  // Online/Offline Status
  updateOnlineStatus() {
    const isOnline = navigator.onLine;
    document.body.classList.toggle('offline', !isOnline);

    const offlineBanner = document.querySelector('.offline-banner');
    if (!isOnline && !offlineBanner) {
      const banner = document.createElement('div');
      banner.className = 'offline-banner';
      banner.textContent = 'You are offline. Some features may be unavailable.';
      document.body.insertBefore(banner, document.body.firstChild);
    } else if (isOnline && offlineBanner) {
      offlineBanner.remove();
    }
  }

  // Utilities
  formatTime(timestamp) {
    const date = new Date(timestamp * 1000);
    return date.toLocaleTimeString('en-US', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit'
    });
  }

  showToast(message, type = 'info') {
    const existing = document.querySelector('.toast');
    if (existing) existing.remove();

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    document.body.appendChild(toast);

    requestAnimationFrame(() => toast.classList.add('visible'));

    setTimeout(() => {
      toast.classList.remove('visible');
      setTimeout(() => toast.remove(), 300);
    }, 3000);
  }

  urlBase64ToUint8Array(base64String) {
    const padding = '='.repeat((4 - base64String.length % 4) % 4);
    const base64 = (base64String + padding)
      .replace(/-/g, '+')
      .replace(/_/g, '/');
    const rawData = window.atob(base64);
    const outputArray = new Uint8Array(rawData.length);
    for (let i = 0; i < rawData.length; ++i) {
      outputArray[i] = rawData.charCodeAt(i);
    }
    return outputArray;
  }
}

// Initialize app when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  window.sentinelApp = new SentinelApp();
});
