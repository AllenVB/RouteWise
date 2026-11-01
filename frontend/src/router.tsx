import { createBrowserRouter, Navigate } from 'react-router';
import { AppShell, type NavItem } from './components/layout/AppShell';
import { LoginPage } from './features/auth/LoginPage';
import { RequireRole } from './features/auth/RequireRole';
import { CourierStopPage } from './features/courier/CourierStopPage';
import { NewShipmentPage } from './features/shipments/NewShipmentPage';
import { ShipmentListPage } from './features/shipments/ShipmentListPage';

// Harita kullanan ekranlar tembel yüklenir: MapLibre tek başına ~1 MB'tır ve
// giriş ya da kargo listesi için indirilmesi gereksizdir.
const verification = () =>
  import('./features/verification/VerificationQueuePage').then((m) => ({ Component: m.VerificationQueuePage }));
const planning = () => import('./features/planning/PlanningPage').then((m) => ({ Component: m.PlanningPage }));
const courierRoute = () => import('./features/courier/CourierRoutePage').then((m) => ({ Component: m.CourierRoutePage }));
const myAddresses = () => import('./features/recipient/MyAddressesPage').then((m) => ({ Component: m.MyAddressesPage }));

// Tembel rota doğrudan açıldığında (sayfa yenileme) modül gelene kadar gösterilir.
const loading = <p className="muted">Yükleniyor…</p>;

const companyNav: NavItem[] = [
  { to: '/firma/kargolar', label: 'Kargolar' },
  { to: '/firma/dogrulama', label: 'Adres doğrulama' },
  { to: '/firma/planlama', label: 'Rota planlama' },
];
const recipientNav: NavItem[] = [{ to: '/alici/adresler', label: 'Adreslerim' }];
const courierNav: NavItem[] = [{ to: '/kurye', label: 'Bugünkü rotam' }];

export const router = createBrowserRouter([
  { path: '/giris', element: <LoginPage /> },
  {
    path: '/firma',
    element: <RequireRole role="COMPANY"><AppShell nav={companyNav} /></RequireRole>,
    children: [
      { index: true, element: <Navigate to="kargolar" replace /> },
      { path: 'kargolar', element: <ShipmentListPage /> },
      { path: 'kargolar/yeni', element: <NewShipmentPage /> },
      { path: 'dogrulama', lazy: verification, hydrateFallbackElement: loading },
      { path: 'planlama', lazy: planning, hydrateFallbackElement: loading },
    ],
  },
  {
    path: '/alici',
    element: <RequireRole role="RECIPIENT"><AppShell nav={recipientNav} /></RequireRole>,
    children: [
      { index: true, element: <Navigate to="adresler" replace /> },
      { path: 'adresler', lazy: myAddresses, hydrateFallbackElement: loading },
    ],
  },
  {
    path: '/kurye',
    element: <RequireRole role="COURIER"><AppShell nav={courierNav} /></RequireRole>,
    children: [
      { index: true, lazy: courierRoute, hydrateFallbackElement: loading },
      { path: 'durak/:id', element: <CourierStopPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/giris" replace /> },
]);
