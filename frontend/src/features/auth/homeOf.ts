import type { Role } from '../../types/api';

export function homeOf(role: Role): string {
  switch (role) {
    case 'COMPANY':
      return '/firma/kargolar';
    case 'RECIPIENT':
      return '/alici/adresler';
    case 'COURIER':
      return '/kurye';
  }
}
