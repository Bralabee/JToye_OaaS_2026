import { cn } from "@/lib/utils"

// Shared loading skeleton with a shimmer sweep (keyframe lives in
// globals.css). Adoption is incremental — existing animate-pulse blocks are
// swapped surface-by-surface in later phases.
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "rounded-md bg-gradient-to-r from-slate-100 via-slate-200 to-slate-100 bg-[length:200%_100%] animate-[shimmer_1.8s_ease-in-out_infinite] motion-reduce:animate-none",
        className
      )}
      {...props}
    />
  )
}

export { Skeleton }
