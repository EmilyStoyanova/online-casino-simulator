import { Link } from 'react-router'
import { secondaryButton } from '../components/buttonStyles'

export function NotFoundPage() {
  return (
    <div className="flex flex-col items-center gap-4 text-center">
      <h1 className="text-3xl font-bold tracking-tight">Page not found</h1>
      <p className="text-zinc-400">The page you are looking for does not exist.</p>
      <Link to="/" className={secondaryButton}>
        Back to the home page
      </Link>
    </div>
  )
}
