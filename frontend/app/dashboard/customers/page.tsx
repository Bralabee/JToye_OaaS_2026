"use client"

import { useEffect, useState } from "react"
import { m } from "framer-motion"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"
import apiClient from "@/lib/api-client"
import { useToast } from "@/hooks/use-toast"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Badge } from "@/components/ui/badge"
import {
  Users,
  Plus,
  Pencil,
  Trash2,
  Mail,
  Phone,
  AlertCircle,
  Calendar,
  ShoppingCart,
  ShieldAlert,
} from "lucide-react"
import Link from "next/link"
import { Pagination } from "@/components/ui/pagination"
import type { Customer, CreateCustomerRequest } from "@/types/api"
import {
  ALLERGENS,
  hasAllergen,
  toggleAllergen,
  getAllergenNames,
} from "@/types/api"
import { formatDistanceToNow } from "date-fns"

const customerSchema = z.object({
  name: z.string().min(1, "Name is required").max(100, "Name too long"),
  email: z.string().email("Invalid email").max(255, "Email too long"),
  phone: z.string().max(20, "Phone too long").optional(),
})

type CustomerFormData = z.infer<typeof customerSchema>

const PAGE_SIZE = 20

export default function CustomersPage() {
  const [customers, setCustomers] = useState<Customer[]>([])
  const [loading, setLoading] = useState(true)
  const [currentPage, setCurrentPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false)
  const [editingCustomer, setEditingCustomer] = useState<Customer | null>(null)
  const [deletingCustomer, setDeletingCustomer] = useState<Customer | null>(null)
  const [allergenRestrictions, setAllergenRestrictions] = useState(0)
  const [submitting, setSubmitting] = useState(false)
  const { toast } = useToast()

  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
  } = useForm<CustomerFormData>({
    resolver: zodResolver(customerSchema),
  })

  useEffect(() => {
    fetchCustomers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage])

  const fetchCustomers = async () => {
    try {
      setLoading(true)
      const response = await apiClient.get(
        `/api/v1/customers?page=${currentPage}&size=${PAGE_SIZE}&sort=createdAt,desc`
      )
      setCustomers(response.data.content || [])
      setTotalPages(response.data.totalPages || 0)
      setTotalElements(response.data.totalElements || 0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to load customers"
      toast({
        variant: "destructive",
        title: "Error loading customers",
        description: errorMessage,
      })
    } finally {
      setLoading(false)
    }
  }

  const openCreateDialog = () => {
    setEditingCustomer(null)
    reset({ name: "", email: "", phone: "" })
    setAllergenRestrictions(0)
    setDialogOpen(true)
  }

  const openEditDialog = (customer: Customer) => {
    setEditingCustomer(customer)
    setValue("name", customer.name)
    setValue("email", customer.email)
    setValue("phone", customer.phone || "")
    setAllergenRestrictions(customer.allergenRestrictions)
    setDialogOpen(true)
  }

  const openDeleteDialog = (customer: Customer) => {
    setDeletingCustomer(customer)
    setDeleteDialogOpen(true)
  }

  const toggleAllergenBit = (bit: number) => {
    setAllergenRestrictions(toggleAllergen(allergenRestrictions, bit))
  }

  const onSubmit = async (data: CustomerFormData) => {
    try {
      setSubmitting(true)

      const payload: CreateCustomerRequest = {
        ...data,
        allergenRestrictions,
      }

      if (editingCustomer) {
        // Update existing customer
        await apiClient.put(`/api/v1/customers/${editingCustomer.id}`, payload)
        toast({
          title: "Customer updated",
          description: `${data.name} has been updated successfully.`,
        })
      } else {
        // Create new customer
        await apiClient.post("/api/v1/customers", payload)
        toast({
          title: "Customer created",
          description: `${data.name} has been created successfully.`,
        })
      }

      setDialogOpen(false)
      reset()
      setAllergenRestrictions(0)
      if (currentPage === 0) fetchCustomers()
      else setCurrentPage(0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : `Failed to ${editingCustomer ? "update" : "create"} customer`
      toast({
        variant: "destructive",
        title: editingCustomer ? "Error updating customer" : "Error creating customer",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!deletingCustomer) return

    try {
      setSubmitting(true)
      await apiClient.delete(`/api/v1/customers/${deletingCustomer.id}`)
      toast({
        title: "Customer deleted",
        description: `${deletingCustomer.name} has been deleted successfully.`,
      })
      setDeleteDialogOpen(false)
      setDeletingCustomer(null)
      if (currentPage === 0) fetchCustomers()
      else setCurrentPage(0)
    } catch (error: unknown) {
      const errorMessage = error instanceof Error ? error.message : "Failed to delete customer"
      toast({
        variant: "destructive",
        title: "Error deleting customer",
        description: errorMessage,
      })
    } finally {
      setSubmitting(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-t-2 border-blue-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <m.div
        initial={{ opacity: 0, y: -20 }}
        animate={{ opacity: 1, y: 0 }}
        className="flex items-center justify-between"
      >
        <div>
          <h1 className="text-4xl font-bold text-slate-900">Customers</h1>
          <p className="mt-2 text-slate-600">
            Manage customer information and allergen restrictions
          </p>
        </div>
        <Button onClick={openCreateDialog} className="gap-2">
          <Plus className="h-4 w-4" />
          Add Customer
        </Button>
      </m.div>

      {/* Customers Table */}
      <m.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.1 }}
      >
        <Card>
          <CardHeader>
            <CardTitle>All Customers</CardTitle>
            <CardDescription>
              {totalElements} customer{totalElements !== 1 ? "s" : ""} in total
            </CardDescription>
          </CardHeader>
          <CardContent>
            {customers.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <Users className="mb-4 h-12 w-12 text-slate-300" />
                <h3 className="mb-2 text-lg font-semibold text-slate-900">
                  No customers yet
                </h3>
                <p className="mb-4 text-sm text-slate-500">
                  Get started by adding your first customer
                </p>
                <Button onClick={openCreateDialog} variant="outline">
                  <Plus className="mr-2 h-4 w-4" />
                  Add Customer
                </Button>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Name</TableHead>
                      <TableHead>Contact</TableHead>
                      <TableHead>Allergen Restrictions</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {customers.map((customer) => {
                      const allergenNames = getAllergenNames(
                        customer.allergenRestrictions
                      )
                      return (
                        <m.tr
                          key={customer.id}
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          className="group"
                        >
                          <TableCell>
                            <div className="flex items-center gap-2">
                              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-gradient-to-br from-orange-400 to-pink-500 text-white font-semibold">
                                {customer.name.charAt(0).toUpperCase()}
                              </div>
                              <div>
                                <div className="font-medium">{customer.name}</div>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="space-y-1">
                              <div className="flex items-center gap-2 text-sm text-slate-600">
                                <Mail className="h-3 w-3" />
                                {customer.email}
                              </div>
                              {customer.phone && (
                                <div className="flex items-center gap-2 text-sm text-slate-600">
                                  <Phone className="h-3 w-3" />
                                  {customer.phone}
                                </div>
                              )}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1">
                              {allergenNames.length === 0 ? (
                                <span className="text-sm text-slate-400">
                                  No restrictions
                                </span>
                              ) : (
                                allergenNames.map((name) => {
                                  const allergen = ALLERGENS.find(
                                    (a) => a.name === name
                                  )
                                  return (
                                    <Badge
                                      key={name}
                                      variant="outline"
                                      className="bg-red-50 text-red-700 border-red-200"
                                    >
                                      {name}
                                    </Badge>
                                  )
                                })
                              )}
                            </div>
                          </TableCell>
                          <TableCell className="text-slate-600">
                            <div className="flex items-center gap-2">
                              <Calendar className="h-4 w-4" />
                              {formatDistanceToNow(new Date(customer.createdAt), {
                                addSuffix: true,
                              })}
                            </div>
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="flex justify-end gap-2">
                              <Link href={`/dashboard/orders?customer=${customer.id}`}>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  className="h-8 w-8 p-0 text-blue-600 hover:bg-blue-50 hover:text-blue-700"
                                  title="View orders"
                                >
                                  <ShoppingCart className="h-4 w-4" />
                                </Button>
                              </Link>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => openEditDialog(customer)}
                                className="h-8 w-8 p-0"
                              >
                                <Pencil className="h-4 w-4" />
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => openDeleteDialog(customer)}
                                className="h-8 w-8 p-0 text-red-600 hover:bg-red-50 hover:text-red-700"
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </div>
                          </TableCell>
                        </m.tr>
                      )
                    })}
                  </TableBody>
                </Table>
              </div>
            )}
            <Pagination
              currentPage={currentPage}
              totalPages={totalPages}
              totalElements={totalElements}
              pageSize={PAGE_SIZE}
              onPageChange={setCurrentPage}
            />
          </CardContent>
        </Card>
      </m.div>

      {/* Create/Edit Dialog */}
      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {editingCustomer ? "Edit Customer" : "Create New Customer"}
            </DialogTitle>
            <DialogDescription>
              {editingCustomer
                ? "Update the customer details below."
                : "Add a new customer to your system."}
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="name">Full Name</Label>
              <Input
                id="name"
                placeholder="e.g., John Doe"
                {...register("name")}
              />
              {errors.name && (
                <p className="text-sm text-red-600">{errors.name.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="email">Email</Label>
              <Input
                id="email"
                type="email"
                placeholder="e.g., john@example.com"
                {...register("email")}
              />
              {errors.email && (
                <p className="text-sm text-red-600">{errors.email.message}</p>
              )}
            </div>

            <div className="space-y-2">
              <Label htmlFor="phone">Phone (Optional)</Label>
              <Input
                id="phone"
                placeholder="e.g., +44 7700 900000"
                {...register("phone")}
              />
              {errors.phone && (
                <p className="text-sm text-red-600">{errors.phone.message}</p>
              )}
            </div>

            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <AlertCircle className="h-4 w-4 text-red-600" />
                <Label>Allergen Restrictions</Label>
              </div>
              <p className="text-sm text-slate-600">
                Select all allergens this customer must avoid
              </p>
              {/*
                UK GDPR Art. 9: allergy details are data concerning health, so recording
                them needs an Art. 9(2) condition — realistically the customer's explicit
                consent. The VENDOR is the controller here (they decide to record it), so
                the duty is theirs and this notice puts it at the point of entry.
                Determination: docs/legal/article-9-allergen-basis.md.
              */}
              <div
                role="note"
                className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800"
              >
                <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                <p>
                  Allergy details are health data. Get the customer&apos;s explicit consent
                  before recording them — you&apos;re responsible for that consent — and clear
                  these boxes if they withdraw it.
                </p>
              </div>
              <div className="grid grid-cols-2 gap-3 rounded-lg border p-4 bg-slate-50">
                {ALLERGENS.map((allergen) => (
                  <label
                    key={allergen.bit}
                    className="flex items-center gap-3 cursor-pointer rounded-md p-2 hover:bg-white transition-colors"
                  >
                    <input
                      type="checkbox"
                      checked={hasAllergen(allergenRestrictions, allergen.bit)}
                      onChange={() => toggleAllergenBit(allergen.bit)}
                      className="h-4 w-4 rounded border-gray-300 text-red-600 focus:ring-red-500"
                    />
                    <span className="text-sm font-medium">{allergen.name}</span>
                  </label>
                ))}
              </div>
            </div>

            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setDialogOpen(false)}
                disabled={submitting}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={submitting}>
                {submitting
                  ? editingCustomer
                    ? "Updating..."
                    : "Creating..."
                  : editingCustomer
                  ? "Update Customer"
                  : "Create Customer"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteDialogOpen} onOpenChange={setDeleteDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Customer</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete{" "}
              <span className="font-semibold">{deletingCustomer?.name}</span>? This
              action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setDeleteDialogOpen(false)}
              disabled={submitting}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={submitting}
            >
              {submitting ? "Deleting..." : "Delete Customer"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
