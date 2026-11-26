import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'
import { useI18n } from '../i18n/useI18n'

export function NotFoundPage() {
  const { t } = useI18n()
  return (
    <div className="flex flex-col items-center gap-4 text-center">
      <h1 className="text-3xl font-bold tracking-tight">{t('notFound.title')}</h1>
      <p className="text-zinc-400">{t('notFound.text')}</p>
      <Link to="/" className={secondaryButton}>
        {t('common.backHome')}
      </Link>
    </div>
  )
}
