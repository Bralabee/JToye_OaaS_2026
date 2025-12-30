"use client"

import { useSession } from "next-auth/react"
import { useEffect, useState } from "react"
import apiClient from "@/lib/api-client"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"

interface ApiTestData {
  content?: Array<{
    id: string
    name: string
    [key: string]: unknown
  }>
  [key: string]: unknown
}

export default function TestPage() {
  const { data: session, status } = useSession()
  const [apiTest, setApiTest] = useState<ApiTestData | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const testApi = async () => {
      try {
        console.log("Testing API call to /shops...")
        const response = await apiClient.get("/shops?size=5")
        console.log("API Response:", response.data)
        setApiTest(response.data)
      } catch (err: unknown) {
        console.error("API Error:", err)
        setError(err instanceof Error ? err.message : String(err))
      }
    }

    if (status === "authenticated") {
      testApi()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status])

  return (
    <div className="space-y-6">
      <h1 className="text-3xl font-bold">🧪 API Test Page</h1>

      <Card>
        <CardHeader>
          <CardTitle>Session Status</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="space-y-2">
            <p><strong>Status:</strong> {status}</p>
            <p><strong>User:</strong> {session?.user?.email || "Not logged in"}</p>
            <p><strong>Access Token:</strong> {session?.accessToken ? `${session.accessToken.substring(0, 50)}...` : "Not found"}</p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>API Test: GET /shops</CardTitle>
        </CardHeader>
        <CardContent>
          {error && (
            <div className="bg-red-50 border border-red-200 p-4 rounded">
              <p className="text-red-800 font-semibold">Error:</p>
              <p className="text-red-600">{error}</p>
            </div>
          )}
          {apiTest && (
            <div className="bg-green-50 border border-green-200 p-4 rounded">
              <p className="text-green-800 font-semibold">Success!</p>
              <pre className="text-sm mt-2 overflow-auto">
                {JSON.stringify(apiTest, null, 2)}
              </pre>
            </div>
          )}
          {!error && !apiTest && status === "authenticated" && (
            <p>Loading...</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Debug Info</CardTitle>
        </CardHeader>
        <CardContent>
          <pre className="text-xs bg-gray-50 p-4 rounded overflow-auto">
            {JSON.stringify({ session, status }, null, 2)}
          </pre>
        </CardContent>
      </Card>
    </div>
  )
}
