/**
 * Minimal application used by {@code com.casino.architecture.ArchitectureRulesTest} to prove that each
 * architecture rule detects its violation. Classes named after a rule violate exactly that rule;
 * all other classes follow the architecture.
 *
 * <p>Deliberately outside {@code com.casino}, so Spring component scanning and JPA entity scanning in
 * the application tests never pick these classes up.
 */
package archfixture;
