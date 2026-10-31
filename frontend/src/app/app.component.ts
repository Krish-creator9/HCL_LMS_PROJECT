import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe, DecimalPipe, JsonPipe } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { catchError, finalize, of } from 'rxjs';

type Row = any;

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormsModule, DatePipe, DecimalPipe, JsonPipe],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit, OnDestroy {
  private api = (typeof window !== 'undefined' && window.location.hostname.includes('onrender.com'))
    ? 'https://ordercraft-backend-427v.onrender.com/api/v1'
    : '/api/v1';

  // Auth & Session State
  token = localStorage.getItem('oc-token') || '';
  companyId = localStorage.getItem('oc-company') || '';
  user: Row = JSON.parse(localStorage.getItem('oc-user') || '{}');
  companies: Row[] = JSON.parse(localStorage.getItem('oc-companies') || '[]');

  // Auth Mode State: 'login' | 'forgot' | 'register'
  authMode: 'login' | 'forgot' | 'register' = 'login';
  forgotStep: 1 | 2 = 1;
  forgotEmail = '';
  resetCode = '';
  newPassword = '';
  showPassword = false;
  showGoogleModal = false;
  googleEmail = 'krishna.dev@gmail.com';
  googleName = 'Krishna Garg';

  login = { email: 'admin@ordercraft.local', password: 'OrderCraft2026!' };
  registerForm = { email: '', password: '', fullName: '', companyName: 'Chicago Plant 01' };

  // App Navigation State
  section = 'dashboard';
  searchTerm = '';
  statusFilter = 'ALL';
  showAdjustment = false;
  showUser = false;
  loading = false;
  today = new Date();
  toast = '';
  error = '';

  // Operational Data
  dashboard: Row = {};
  orders: Row[] = [];
  workOrders: Row[] = [];
  inventory: Row[] = [];
  movements: Row[] = [];
  purchaseOrders: Row[] = [];
  customers: Row[] = [];
  suppliers: Row[] = [];
  products: Row[] = [];
  warehouses: Row[] = [];
  users: Row[] = [];
  shipments: Row[] = [];
  invoices: Row[] = [];
  orderLines: Row[] = [];
  shopFloor: Row[] = [];
  workCenters: Row[] = [];
  workSchedule: Row[] = [];
  payrollRuns: Row[] = [];
  employees: Row[] = [];
  companyProfile: Row = { baseCurrency: 'USD', taxProfile: '', fiscalYearStartMonth: 1 };
  consolidated: Row = { from: '', to: '', reportingCurrency: 'USD' };

  // Forms
  orderForm: Row = { customerId: '', productId: '', quantity: 1, dueDate: '' };
  poForm: Row = { supplierId: '', productId: '', quantity: 1 };
  adjustmentForm: Row = { productId: '', warehouseId: '', quantity: 0, reason: '' };
  userForm: Row = { email: '', name: '', password: '', role: 'OPERATOR' };
  shipmentForm: Row = { orderId: '', orderLineId: '', quantity: 1, carrierCode: 'LOCAL', trackingNumber: '' };
  scheduleForm: Row = { workOrderId: '', workCenterId: '', startsAt: new Date().toISOString().slice(0, 16) };
  employeeForm: Row = { employeeCode: '', firstName: '', lastName: '', email: '', department: '', payType: 'SALARY', payRate: 0, taxWithholdingRate: 0 };
  companyForm: Row = { code: '', name: '', baseCurrency: 'USD', taxProfile: '', fiscalYearStartMonth: 1 };
  financeUserForm: Row = { name: '', email: '', password: '' };
  exchangeRate: Row = { fromCurrency: 'USD', toCurrency: 'EUR', effectiveOn: new Date().toISOString().slice(0, 10), rate: 1 };

  reportingFrom = '';
  reportingTo = '';
  reportingCurrency = 'USD';
  payrollHours = 40;
  paymentAmount = 0;
  paymentMethod = 'BANK_TRANSFER';
  payrollPeriodStart = '';
  payrollPeriodEnd = '';
  showOrder = false;
  showPo = false;
  navMenuOpen = false;

  // Real-Time Entity Creation States
  showCustomerModal = false;
  customerForm = { name: '', code: '', email: '' };
  showSupplierModal = false;
  supplierForm = { name: '', code: '', leadDays: 5 };
  showProductModal = false;
  productForm = { sku: '', name: '', kind: 'FINISHED', unit: 'ea', reorderPoint: 0 };

  // Database Explorer & Real-Time Sync State
  dbStatus: Row = {};
  dbTables: Row[] = [];
  selectedTable = 'user_account';
  tableData: Row = { columns: [], rows: [], totalRows: 0 };
  dbSearchTerm = '';
  tableSearchTerm = '';
  dbLoading = false;
  showInsertModal = false;
  insertForm: Record<string, any> = {};
  insertJsonMode = false;
  insertJsonString = '{}';
  liveSyncActive = false;
  liveSyncTimer: any = null;

  constructor() {
    this.http = inject(HttpClient);
  }

  ngOnInit() {
    if (this.token) {
      this.load();
    }
  }

  headers(): HttpHeaders {
    return new HttpHeaders({
      Authorization: `Bearer ${this.token}`,
      ...(this.companyId ? { 'X-Company-Id': this.companyId } : {})
    });
  }

  request<T = any>(path: string, method: 'get' | 'post', body?: any) {
    const headers = this.headers();
    return method === 'get'
      ? this.http.get<T>(this.api + path, { headers })
      : this.http.post<T>(this.api + path, body, { headers });
  }

  // --- Authentication Methods ---

  signIn() {
    this.error = '';
    this.loading = true;
    this.http.post<Row>(this.api + '/auth/login', this.login).subscribe({
      next: r => this.processAuthSuccess(r, 'Signed in successfully'),
      error: e => {
        this.loading = false;
        this.error = e.error?.message || 'Could not sign in. Check your email and password.';
      }
    });
  }

  googleSignIn(emailOverride?: string, nameOverride?: string) {
    this.error = '';
    this.loading = true;
    const payload = {
      email: emailOverride || this.googleEmail,
      name: nameOverride || this.googleName,
      googleId: 'google_oauth_' + Date.now(),
      avatarUrl: `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(nameOverride || this.googleName)}`
    };

    this.http.post<Row>(this.api + '/auth/google', payload).subscribe({
      next: r => {
        this.showGoogleModal = false;
        this.processAuthSuccess(r, `Connected via Google (${payload.email})`);
      },
      error: e => {
        this.loading = false;
        this.error = e.error?.message || 'Google authentication failed';
      }
    });
  }

  requestPasswordReset() {
    if (!this.forgotEmail) {
      this.error = 'Please enter your email address.';
      return;
    }
    this.error = '';
    this.loading = true;
    this.http.post<Row>(this.api + '/auth/forgot-password', { email: this.forgotEmail }).subscribe({
      next: r => {
        this.loading = false;
        this.forgotStep = 2;
        if (r.resetToken) {
          this.resetCode = r.resetToken;
        }
        this.notify(r.message || 'Reset code sent to your email.');
      },
      error: e => {
        this.loading = false;
        this.error = e.error?.message || 'Unable to generate reset instructions.';
      }
    });
  }

  submitPasswordReset() {
    if (!this.resetCode || !this.newPassword) {
      this.error = 'Please enter both the reset code and your new password.';
      return;
    }
    this.error = '';
    this.loading = true;
    this.http.post<Row>(this.api + '/auth/reset-password', {
      email: this.forgotEmail,
      token: this.resetCode,
      newPassword: this.newPassword
    }).subscribe({
      next: r => {
        this.loading = false;
        this.login.email = this.forgotEmail;
        this.login.password = this.newPassword;
        this.authMode = 'login';
        this.forgotStep = 1;
        this.forgotEmail = '';
        this.resetCode = '';
        this.newPassword = '';
        this.notify('Password successfully updated! You can now sign in.');
      },
      error: e => {
        this.loading = false;
        this.error = e.error?.message || 'Invalid or expired reset code.';
      }
    });
  }

  registerUser() {
    if (!this.registerForm.email || !this.registerForm.password || !this.registerForm.fullName) {
      this.error = 'Please fill out all required fields.';
      return;
    }
    this.error = '';
    this.loading = true;
    this.http.post<Row>(this.api + '/auth/register', this.registerForm).subscribe({
      next: r => this.processAuthSuccess(r, 'Account created and plant access established!'),
      error: e => {
        this.loading = false;
        this.error = e.error?.message || 'Registration failed. Email may already be in use.';
      }
    });
  }

  quickProfile(type: 'admin' | 'google' | 'operator') {
    if (type === 'admin') {
      this.login.email = 'admin@ordercraft.local';
      this.login.password = 'OrderCraft2026!';
      this.signIn();
    } else if (type === 'google') {
      this.googleSignIn('krishna.dev@gmail.com', 'Krishna Garg');
    } else {
      this.googleSignIn('floor.operator@ordercraft.local', 'Alex Morgan');
    }
  }

  private processAuthSuccess(r: Row, message: string) {
    this.token = r.token;
    this.user = { ...r.user, role: r.role };
    this.companies = r.companies;
    this.companyId = String(r.companies[0]?.id || '1');

    localStorage.setItem('oc-token', this.token);
    localStorage.setItem('oc-user', JSON.stringify(this.user));
    localStorage.setItem('oc-companies', JSON.stringify(this.companies));
    localStorage.setItem('oc-company', this.companyId);

    this.loading = false;
    this.authMode = 'login';
    this.notify(message);
    this.load();
  }

  signOut() {
    localStorage.clear();
    this.token = '';
    this.user = {};
    this.companies = [];
    this.section = 'dashboard';
  }

  selectCompany() {
    localStorage.setItem('oc-company', this.companyId);
    this.load();
  }

  // --- Data Loading & Navigation ---

  navigate(section: string) {
    this.section = section;
    this.searchTerm = '';
    this.statusFilter = 'ALL';
    this.navMenuOpen = false;
    if (this.section !== 'database' && this.liveSyncActive) {
      this.stopLiveSync();
    }
    this.load();
  }

  load() {
    if (!this.token) return;
    this.loading = true;
    this.error = '';
    const finish = () => (this.loading = false);
    const fail = (e: any) => {
      this.loading = false;
      if (e.status === 401) this.signOut();
      else this.error = e.error?.message || 'Unable to load this view.';
    };
    const get = (path: string, next: (x: any) => void) =>
      this.request(path, 'get')
        .pipe(
          catchError(e => {
            fail(e);
            return of(null);
          }),
          finalize(finish)
        )
        .subscribe(x => {
          if (x !== null) {
            finish();
            next(x);
          }
        });

    if (this.section === 'dashboard') get('/dashboard', x => (this.dashboard = x));
    if (this.section === 'orders') get('/orders', x => (this.orders = x));
    if (this.section === 'production') get('/work-orders', x => (this.workOrders = x));
    if (this.section === 'inventory') {
      this.request('/inventory', 'get').subscribe({ next: x => (this.inventory = x as unknown as Row[]), error: fail });
      get('/inventory/movements', x => (this.movements = x));
    }
    if (this.section === 'purchasing') {
      get('/purchase-orders', x => (this.purchaseOrders = x));
      this.request('/warehouses', 'get').subscribe({ next: x => (this.warehouses = x as unknown as Row[]), error: fail });
    }
    if (this.section === 'master-data') {
      this.request('/customers', 'get').subscribe({ next: x => (this.customers = x as unknown as Row[]), error: fail });
      this.request('/suppliers', 'get').subscribe({ next: x => (this.suppliers = x as unknown as Row[]), error: fail });
      this.request('/products', 'get').subscribe({ next: x => (this.products = x as unknown as Row[]), error: fail });
      this.request('/warehouses', 'get').subscribe({ next: x => (this.warehouses = x as unknown as Row[]), error: fail });
      this.loading = false;
    }
    if (this.section === 'admin') get('/admin/users', x => (this.users = x));
    if (this.section === 'fulfillment') {
      get('/shipments', x => (this.shipments = x));
      get('/invoices', x => (this.invoices = x));
      get('/orders', x => (this.orders = x));
    }
    if (this.section === 'factory') {
      get('/shop-floor', x => (this.shopFloor = x));
      get('/work-centers', x => (this.workCenters = x));
      get('/schedule', x => (this.workSchedule = x));
    }
    if (this.section === 'payroll') {
      get('/payroll/employees', x => (this.employees = x));
      get('/payroll/runs', x => (this.payrollRuns = x));
      this.request('/company/finance-profile', 'get').subscribe({ next: x => (this.companyProfile = x as Row), error: fail });
    }
    if (this.section === 'company-settings') {
      this.request('/company/finance-profile', 'get').subscribe({ next: x => (this.companyProfile = x as Row), error: fail });
      this.request('/companies', 'get').subscribe({
        next: x => {
          this.companies = x as unknown as Row[];
          localStorage.setItem('oc-companies', JSON.stringify(this.companies));
        },
        error: fail
      });
    }
    if (this.section === 'consolidated') this.loadConsolidated();

    // Database Explorer
    if (this.section === 'database') {
      this.loadDatabaseInfo();
    }
  }

  // --- Database Explorer Methods ---

  loadDatabaseInfo() {
    this.dbLoading = true;
    this.request('/database/status', 'get').subscribe({
      next: status => {
        this.dbStatus = status;
        this.request<any[]>('/database/tables', 'get').subscribe({
          next: tables => {
            this.dbTables = tables;
            this.dbLoading = false;
            if (this.dbTables.length && !this.selectedTable) {
              this.selectDatabaseTable(this.dbTables[0].tableName);
            } else if (this.selectedTable) {
              this.selectDatabaseTable(this.selectedTable);
            }
          },
          error: () => (this.dbLoading = false)
        });
      },
      error: () => (this.dbLoading = false)
    });
  }

  selectDatabaseTable(tableName: string) {
    this.selectedTable = tableName;
    this.dbLoading = true;
    this.tableSearchTerm = '';
    this.request(`/database/tables/${tableName}`, 'get').subscribe({
      next: data => {
        this.tableData = data;
        this.dbLoading = false;
      },
      error: e => {
        this.dbLoading = false;
        this.error = e.error?.message || 'Could not load table data';
      }
    });
  }

  filteredTables(): Row[] {
    const q = this.dbSearchTerm.trim().toLowerCase();
    if (!q) return this.dbTables;
    return this.dbTables.filter(t => (t.tableName || '').toLowerCase().includes(q));
  }

  filteredTableRows(): Row[] {
    if (!this.tableData?.rows) return [];
    const q = this.tableSearchTerm.trim().toLowerCase();
    if (!q) return this.tableData.rows;
    return this.tableData.rows.filter((row: any) =>
      Object.values(row).some(v => String(v ?? '').toLowerCase().includes(q))
    );
  }

  copyDbConnection() {
    const conn = `mysql -h 127.0.0.1 -P 3306 -u ordercraft_app -pOrderCraft2026! ordercraft`;
    navigator.clipboard.writeText(conn).then(() => {
      this.notify('MySQL connection string copied to clipboard!');
    });
  }

  // --- Real-Time Database Explorer Sync & Live Insertion ---

  toggleLiveSync() {
    this.liveSyncActive = !this.liveSyncActive;
    if (this.liveSyncActive) {
      this.notify('🔴 Real-time Live Sync active (refreshing every 3s)');
      this.liveSyncTimer = setInterval(() => {
        if (this.section === 'database' && this.selectedTable) {
          this.silentRefreshDatabaseTable();
        }
      }, 3000);
    } else {
      this.stopLiveSync();
      this.notify('Real-time sync paused');
    }
  }

  stopLiveSync() {
    this.liveSyncActive = false;
    if (this.liveSyncTimer) {
      clearInterval(this.liveSyncTimer);
      this.liveSyncTimer = null;
    }
  }

  silentRefreshDatabaseTable() {
    if (!this.selectedTable) return;
    this.request(`/database/tables/${this.selectedTable}`, 'get').subscribe({
      next: data => {
        this.tableData = data;
      },
      error: () => {}
    });
  }

  openInsertModal() {
    this.insertForm = {};
    if (this.tableData?.columns) {
      for (const col of this.tableData.columns) {
        const name = col.columnName;
        if (name === 'id') continue;
        if (name === 'tenant_id') {
          this.insertForm[name] = this.dbStatus?.currentTenant || 1;
          continue;
        }
        if (name === 'company_id') {
          this.insertForm[name] = this.dbStatus?.currentCompany || this.companyId || 1;
          continue;
        }
        if (name === 'active' || name === 'enabled') {
          this.insertForm[name] = true;
          continue;
        }
        this.insertForm[name] = '';
      }
    }
    this.insertJsonString = JSON.stringify(this.insertForm, null, 2);
    this.showInsertModal = true;
  }

  submitInsertRow() {
    let payload: Record<string, any> = {};
    if (this.insertJsonMode) {
      try {
        payload = JSON.parse(this.insertJsonString);
      } catch (e: any) {
        this.error = 'Invalid JSON: ' + e.message;
        return;
      }
    } else {
      payload = { ...this.insertForm };
    }

    this.dbLoading = true;
    this.request(`/database/tables/${this.selectedTable}/rows`, 'post', payload).subscribe({
      next: () => {
        this.dbLoading = false;
        this.showInsertModal = false;
        this.notify(`✓ Real-time record inserted into ${this.selectedTable}!`);
        this.selectDatabaseTable(this.selectedTable);
      },
      error: err => {
        this.dbLoading = false;
        this.error = err.error?.message || 'Could not insert record into MySQL';
      }
    });
  }

  ngOnDestroy() {
    this.stopLiveSync();
  }

  // --- Operational Actions ---

  includesSearch(row: Row, fields: string[]) {
    const q = this.searchTerm.trim().toLowerCase();
    return !q || fields.some(field => String(row[field] ?? '').toLowerCase().includes(q));
  }

  visibleOrders() {
    return this.orders.filter(o => this.includesSearch(o, ['orderNo', 'customer']) && (this.statusFilter === 'ALL' || o.status === this.statusFilter));
  }

  visibleWorkOrders() {
    return this.workOrders.filter(w => this.includesSearch(w, ['workNo', 'product', 'sku', 'orderNo']) && (this.statusFilter === 'ALL' || w.status === this.statusFilter));
  }

  visibleInventory() {
    return this.inventory.filter(i => this.includesSearch(i, ['sku', 'name', 'kind']));
  }

  visiblePurchaseOrders() {
    return this.purchaseOrders.filter(p => this.includesSearch(p, ['poNo', 'supplier']) && (this.statusFilter === 'ALL' || p.status === this.statusFilter));
  }

  createOrder() {
    this.request('/orders', 'post', this.orderForm).subscribe({
      next: () => {
        this.showOrder = false;
        this.orderForm = { customerId: '', productId: '', quantity: 1, dueDate: '' };
        this.notify('Sales order created');
        this.navigate('orders');
      },
      error: e => (this.error = e.error?.message || 'Order could not be created')
    });
  }

  confirmOrder(o: Row) {
    this.request(`/orders/${o.id}/confirm`, 'post', {}).subscribe({
      next: () => {
        this.notify('Order confirmed and work order created');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Order could not be confirmed')
    });
  }

  reserve(w: Row) {
    this.request(`/work-orders/${w.id}/reserve`, 'post', {}).subscribe({
      next: () => {
        this.notify('Materials reserved');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Materials are not available yet')
    });
  }

  report(w: Row) {
    const quantity = window.prompt(`Good quantity to report for ${w.workNo} (remaining ${Number(w.quantity) - Number(w.produced)} ${w.unit || 'ea'})`, '1');
    if (!quantity) return;
    this.request(`/work-orders/${w.id}/report`, 'post', { quantity: Number(quantity), idempotencyKey: crypto.randomUUID() }).subscribe({
      next: () => {
        this.notify('Production reported and stock updated');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Production report was rejected')
    });
  }

  createAdjustment() {
    this.adjustmentForm.idempotencyKey = crypto.randomUUID();
    this.request('/inventory/adjustments', 'post', this.adjustmentForm).subscribe({
      next: () => {
        this.showAdjustment = false;
        this.notify('Stock adjustment recorded');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Adjustment rejected')
    });
  }

  createUser() {
    this.request('/admin/users', 'post', this.userForm).subscribe({
      next: () => {
        this.showUser = false;
        this.userForm = { email: '', name: '', password: '', role: 'OPERATOR' };
        this.notify('User access created');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'User could not be created')
    });
  }

  createPo() {
    this.request('/purchase-orders', 'post', this.poForm).subscribe({
      next: () => {
        this.showPo = false;
        this.notify('Purchase order created');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Purchase order could not be created')
    });
  }

  createCustomer() {
    if (!this.customerForm.name) {
      this.error = 'Customer name is required';
      return;
    }
    this.request('/customers', 'post', this.customerForm).subscribe({
      next: () => {
        this.showCustomerModal = false;
        this.customerForm = { name: '', code: '', email: '' };
        this.notify('Customer added in real time');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Could not add customer')
    });
  }

  createSupplier() {
    if (!this.supplierForm.name) {
      this.error = 'Supplier name is required';
      return;
    }
    this.request('/suppliers', 'post', this.supplierForm).subscribe({
      next: () => {
        this.showSupplierModal = false;
        this.supplierForm = { name: '', code: '', leadDays: 5 };
        this.notify('Supplier added in real time');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Could not add supplier')
    });
  }

  createProduct() {
    if (!this.productForm.sku || !this.productForm.name) {
      this.error = 'SKU and product name are required';
      return;
    }
    this.request('/products', 'post', this.productForm).subscribe({
      next: () => {
        this.showProductModal = false;
        this.productForm = { sku: '', name: '', kind: 'FINISHED', unit: 'ea', reorderPoint: 0 };
        this.notify('Product added in real time');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Could not add product')
    });
  }

  receive(po: Row) {
    this.request(`/purchase-orders/${po.id}/receive`, 'post', {
      lineId: po.lineId,
      warehouseId: this.warehouses[0]?.id,
      quantity: 1,
      idempotencyKey: crypto.randomUUID()
    }).subscribe({
      next: () => {
        this.notify('Receipt recorded');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Receipt could not be recorded')
    });
  }

  notify(message: string) {
    this.toast = message;
    setTimeout(() => (this.toast = ''), 3500);
  }

  export(resource: string) {
    this.http.get(this.api + `/exports/${resource}.csv`, { headers: this.headers(), responseType: 'blob' }).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${resource}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: e => (this.error = e.error?.message || 'Export failed')
    });
  }

  statusClass(status: string) {
    return (status || '').toLowerCase().replaceAll('_', '-');
  }

  remaining(w: Row) {
    return Number(w.quantity) - Number(w.produced);
  }

  available(item: Row) {
    return Number(item.onHand) - Number(item.reserved);
  }

  stockWidth(item: Row) {
    return Math.min(100, (this.available(item) / Math.max(1, Number(item.reorderPoint))) * 100);
  }

  shortageCount() {
    return this.workOrders.filter(w => w.status === 'MATERIAL_SHORTAGE').length;
  }

  completedCount() {
    return this.workOrders.filter(w => w.status === 'COMPLETED').length;
  }

  lowInventoryCount() {
    return this.inventory.filter(x => x.kind !== 'FINISHED' && this.available(x) <= Number(x.reorderPoint)).length;
  }

  issueInvoice(shipment: Row) {
    this.request(`/invoices/from-shipment?shipmentId=${shipment.id}`, 'post', {}).subscribe({
      next: r => {
        this.notify(`Invoice ${(r as any).invoiceNo || ''} issued`);
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Invoice could not be issued')
    });
  }

  loadOrderLines(orderId: string) {
    this.orderLines = [];
    this.shipmentForm.orderLineId = '';
    if (!orderId) return;
    this.request(`/orders/${orderId}/lines`, 'get').subscribe({
      next: x => (this.orderLines = x as unknown as Row[]),
      error: e => (this.error = e.error?.message || 'Order lines could not be loaded')
    });
  }

  createShipment() {
    const orderId = Number(this.shipmentForm.orderId);
    const orderLineId = Number(this.shipmentForm.orderLineId);
    const quantity = Number(this.shipmentForm.quantity);
    if (!orderId || !orderLineId || quantity <= 0) {
      this.error = 'Choose an order line and enter a positive shipment quantity.';
      return;
    }
    const payload = {
      orderId,
      lines: [{ orderLineId, quantity }],
      carrierCode: this.shipmentForm.carrierCode,
      trackingNumber: this.shipmentForm.trackingNumber,
      idempotencyKey: crypto.randomUUID()
    };
    this.request('/shipments', 'post', payload).subscribe({
      next: () => {
        this.notify('Shipment recorded and finished inventory reduced');
        this.shipmentForm = { orderId: '', orderLineId: '', quantity: 1, carrierCode: 'LOCAL', trackingNumber: '' };
        this.orderLines = [];
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Shipment could not be recorded')
    });
  }

  recordInvoicePayment(invoice: Row) {
    if (Number(this.paymentAmount) <= 0) {
      this.error = 'Enter a positive payment amount first.';
      return;
    }
    this.request(`/invoices/${invoice.id}/payments`, 'post', {
      eventType: 'PAYMENT',
      paymentMethod: this.paymentMethod,
      amount: Number(this.paymentAmount),
      reference: 'Portal payment',
      idempotencyKey: crypto.randomUUID()
    }).subscribe({
      next: () => {
        this.paymentAmount = 0;
        this.notify('Payment recorded');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Payment could not be recorded')
    });
  }

  voidInvoice(invoice: Row) {
    this.request(`/invoices/${invoice.id}/void`, 'post', {}).subscribe({
      next: () => {
        this.notify('Invoice voided with a reversal entry');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Invoice could not be voided')
    });
  }

  loadConsolidated() {
    if (!this.reportingFrom || !this.reportingTo) return;
    this.request(`/reports/consolidated?from=${this.reportingFrom}&to=${this.reportingTo}&currency=${this.reportingCurrency}`, 'get').subscribe({
      next: x => (this.consolidated = x as Row),
      error: e => (this.error = e.error?.message || 'Consolidated report could not be loaded')
    });
  }

  saveCompanyProfile() {
    this.request('/company/finance-profile', 'post', this.companyProfile).subscribe({
      next: x => {
        this.companyProfile = x as Row;
        this.notify('Company finance profile saved');
      },
      error: e => (this.error = e.error?.message || 'Company profile could not be saved')
    });
  }

  saveEmployee() {
    this.request('/payroll/employees', 'post', this.employeeForm).subscribe({
      next: () => {
        this.employeeForm = { employeeCode: '', firstName: '', lastName: '', email: '', department: '', payType: 'SALARY', payRate: 0, taxWithholdingRate: 0 };
        this.notify('Employee added');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Employee could not be saved')
    });
  }

  createFinanceUser() {
    this.request('/admin/users', 'post', { ...this.financeUserForm, role: 'FINANCE' }).subscribe({
      next: () => {
        this.financeUserForm = { name: '', email: '', password: '' };
        this.notify('Finance user assigned to this company');
      },
      error: e => (this.error = e.error?.message || 'Finance user could not be created')
    });
  }

  createCompany() {
    this.request('/admin/companies', 'post', this.companyForm).subscribe({
      next: () => {
        this.notify('Company created and assigned to your account');
        this.companyForm = { code: '', name: '', baseCurrency: 'USD', taxProfile: '', fiscalYearStartMonth: 1 };
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Company could not be created')
    });
  }

  saveExchangeRate() {
    const rate = {
      fromCurrency: this.exchangeRate.fromCurrency,
      toCurrency: this.exchangeRate.toCurrency,
      effectiveOn: this.exchangeRate.effectiveOn,
      rate: Number(this.exchangeRate.rate)
    };
    this.request('/finance/exchange-rates', 'post', rate).subscribe({
      next: () => {
        this.notify('Exchange rate saved');
        this.loadConsolidated();
      },
      error: e => (this.error = e.error?.message || 'Exchange rate could not be saved')
    });
  }

  createPayrollRun() {
    if (!this.payrollPeriodStart || !this.payrollPeriodEnd || !this.employees.length) {
      this.error = 'Choose a pay period and add employees before creating a payroll run.';
      return;
    }
    const lines = this.employees.map(e => ({ employeeId: e.id, regularHours: Number(this.payrollHours), overtimeHours: 0 }));
    this.request('/payroll/runs', 'post', { periodStart: this.payrollPeriodStart, periodEnd: this.payrollPeriodEnd, lines }).subscribe({
      next: () => {
        this.notify('Payroll draft created');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Payroll draft could not be created')
    });
  }

  payrollAction(run: Row, action: string) {
    this.request(`/payroll/runs/${run.id}/${action}`, 'post', {}).subscribe({
      next: () => {
        this.notify(`Payroll ${action} complete`);
        this.load();
      },
      error: e => (this.error = e.error?.message || `Payroll ${action} failed`)
    });
  }

  reportShopFloor(work: Row) {
    const quantity = Math.min(1, Number(work.remaining || 0));
    if (quantity <= 0) {
      this.error = 'This work order has no remaining quantity.';
      return;
    }
    this.request(`/work-orders/${work.id}/report`, 'post', { quantity, idempotencyKey: crypto.randomUUID() }).subscribe({
      next: () => {
        this.notify('Shop-floor report recorded');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Production report could not be recorded')
    });
  }

  createSchedule() {
    const payload = {
      workOrderId: Number(this.scheduleForm.workOrderId),
      workCenterId: Number(this.scheduleForm.workCenterId),
      startsAt: this.scheduleForm.startsAt,
      idempotencyKey: crypto.randomUUID()
    };
    if (!payload.workOrderId || !payload.workCenterId || !payload.startsAt) {
      this.error = 'Choose a work order, work center, and start time.';
      return;
    }
    this.request('/schedule', 'post', payload).subscribe({
      next: () => {
        this.notify('Operation scheduled');
        this.load();
      },
      error: e => (this.error = e.error?.message || 'Work could not be scheduled')
    });
  }

  title() {
    return (
      ({
        dashboard: 'Operations Hub',
        orders: 'Sales Orders',
        production: 'Production Planning',
        inventory: 'Inventory Ledger',
        purchasing: 'Purchasing',
        'master-data': 'Master Data',
        admin: 'Administration',
        fulfillment: 'Shipping & Invoicing',
        factory: 'Factory Floor',
        payroll: 'Payroll',
        'company-settings': 'Company Books',
        consolidated: 'Consolidated Reporting',
        database: 'Database Explorer & Live Data'
      } as any)[this.section] || 'Operations Hub'
    );
  }
}

export { AppComponent as App };
