"use client"

import { signIn } from "next-auth/react"
import { motion } from "framer-motion"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { BRAND } from "@/lib/brand"
import { fadeIn, scaleFade, useReducedMotionSafe } from "@/lib/motion"
import { cn } from "@/lib/utils"

export default function SignInPage() {
  const cardVariants = useReducedMotionSafe(scaleFade)
  const bgVariants = useReducedMotionSafe(fadeIn)

  return (
    <motion.div
      variants={bgVariants}
      initial="hidden"
      animate="visible"
      className={cn(
        "min-h-screen flex items-center justify-center p-4",
        "bg-brand-primary/5",
      )}
    >
      <motion.div variants={cardVariants} initial="hidden" animate="visible" className="w-full max-w-md">
        <Card variant="lifted" className="shadow-float rounded-xl p-2">
          <CardHeader className="space-y-3 text-center pb-6">
            <div className="mx-auto flex h-16 w-16 items-center justify-center">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={BRAND.marks.icon}
                alt={`${BRAND.name} mark`}
                className="h-16 w-16 text-brand-primary"
                width={64}
                height={64}
              />
            </div>
            <CardTitle className="font-display text-3xl">Welcome to {BRAND.name}</CardTitle>
            <CardDescription className="text-base text-ink-secondary">
              Sign in to manage your shops, products, and orders.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Button
              variant="primary"
              size="lg"
              onClick={() => signIn("keycloak", { callbackUrl: "/dashboard" })}
              className="w-full font-medium"
            >
              Sign in with Keycloak
            </Button>
            <p className="text-caption text-center text-ink-tertiary font-display italic">
              {BRAND.tagline}
            </p>
          </CardContent>
        </Card>
      </motion.div>
    </motion.div>
  )
}
