<template>
  <router-view />
</template>

<script setup>
</script>

<style>
:root {
  --color-primary:    #4f6ef7;
  --color-success:    #22c55e;
  --color-warning:    #f59e0b;
  --color-danger:     #ef4444;
  --color-info:       #64748b;

  --sidebar-bg:       #0f172a;
  --sidebar-hover:    #1e293b;
  --sidebar-active:   #1d4ed8;
  --sidebar-text:     #94a3b8;
  --sidebar-active-text: #fff;

  --header-bg:        #fff;
  --content-bg:       #f1f5f9;
  --card-bg:          #fff;
  --border-color:     #e2e8f0;

  --text-primary:     #0f172a;
  --text-secondary:   #64748b;
  --text-muted:       #94a3b8;

  --radius-sm:        6px;
  --radius-md:        10px;
  --radius-lg:        14px;

  --shadow-sm:        0 1px 3px rgba(0,0,0,.06), 0 1px 2px rgba(0,0,0,.04);
  --shadow-md:        0 4px 12px rgba(0,0,0,.08);
  --shadow-lg:        0 10px 30px rgba(0,0,0,.12);

  --font-mono:        'JetBrains Mono', 'Fira Code', 'Cascadia Code', monospace;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Inter', 'Segoe UI', sans-serif;
  -webkit-font-smoothing: antialiased;
  color: var(--text-primary);
}

/* Global page header */
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 10px;
}

/* Monospace text */
.mono {
  font-family: var(--font-mono);
  font-size: .82rem;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
  color: #334155;
}

/* Stat cards */
.stat-card {
  text-align: center;
  padding: 8px 0 4px;
  border-radius: var(--radius-md) !important;
  border: 1px solid var(--border-color) !important;
  box-shadow: var(--shadow-sm) !important;
  transition: box-shadow .2s;
}
.stat-card:hover { box-shadow: var(--shadow-md) !important; }
.stat-label {
  font-size: .75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .06em;
  color: var(--text-muted);
  margin-bottom: 10px;
}
.stat-value {
  font-size: 1.6rem;
  font-weight: 800;
  color: var(--text-primary);
  line-height: 1;
}

/* Element Plus overrides */
.el-card {
  border-radius: var(--radius-md) !important;
  border: 1px solid var(--border-color) !important;
  box-shadow: var(--shadow-sm) !important;
}
.el-card__header {
  font-weight: 600;
  color: var(--text-primary);
  border-bottom: 1px solid var(--border-color);
  padding: 14px 20px;
}
.el-table th.el-table__cell {
  background: #f8fafc !important;
  color: var(--text-secondary);
  font-size: .78rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .05em;
}
.el-table td.el-table__cell { padding: 12px 0; }
.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell {
  background: #fafbfc;
}
.el-button { border-radius: var(--radius-sm) !important; font-weight: 500; }
.el-tag { border-radius: 20px !important; font-weight: 600; }
</style>
