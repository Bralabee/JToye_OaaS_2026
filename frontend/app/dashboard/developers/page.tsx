"use client"

/**
 * RED-phase placeholder (37-46 Task 1): the Developers page (UI-SPEC § E1) lands in the GREEN
 * commit. It renders only the declared width tier, so the new suite loads and fails on its
 * assertions rather than on a missing module.
 */
export default function DevelopersPage() {
  return <div data-width-tier="index" />
}
