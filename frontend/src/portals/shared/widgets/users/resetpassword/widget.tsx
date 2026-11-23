import type { WidgetRuntimeProps } from '../../core/manifest';
import { UsersOrganization } from '../organization/widget';
import type { UsersOrganizationConfig } from '../organization';
import type { PasswordResetConfig } from './index';

export function PasswordResetWidget(props: WidgetRuntimeProps<PasswordResetConfig>) {
  const userProps: WidgetRuntimeProps<UsersOrganizationConfig> = {
    ...props,
    config: { scope: props.config.scope ?? 'own', mode: 'reset' },
  };
  return <UsersOrganization {...userProps} />;
}
